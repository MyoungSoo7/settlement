# CI 정본 — GitHub Actions × k3s 하이브리드

이미지 빌드·푸시만 k3s 홈랩으로 내리고, **테스트·게이트는 GitHub Actions 에 남긴다.** 이 문서는 그
경계가 어디에 그어져 있고 왜 거기인지를 한 곳에 모은다. 운영 절차(설치·실행·문제 해결)는
[`k8s/buildkit/README.md`](../k8s/buildkit/README.md) 가 정본이며 여기서 복제하지 않는다.

## 경계

| 단계 | 어디서 | 근거 |
| --- | --- | --- |
| 변경 경로 탐지 · 백엔드 빌드/테스트/JaCoCo · 프론트 빌드/테스트 · 하네스 가드 · SAST | **GitHub Actions** | `main` 보호 규칙의 **필수 체크 6종**이다. 여기 손대면 PR 이 머지 불가가 된다 |
| Testcontainers 통합테스트 | **GitHub Actions** | k3s 는 containerd 라 Docker 소켓이 없다. 옮기려면 DinD(privileged) 사이드카 + PG 17·ES 8.11 서비스 컨테이너를 전부 재현해야 한다 |
| **이미지 빌드 + GHCR 푸시** | **k3s** | `backend-ghcr`·`frontend-ghcr` 는 필수 체크가 아니다. 러너 시간 절감이 가장 크고, 실패해도 머지를 막지 않는다 |
| 컨테이너 CVE 스캔 | **k3s** (`build.sh --scan`) | 위 잡을 끄면 거기 붙어 있던 Trivy CRITICAL 게이트도 같이 사라진다 |

`main` 필수 체크 6종: `Detect changed paths` · `Backend - Build/Test/JaCoCo/SonarCloud` ·
`Frontend - Production Build & Quality` · `Frontend - Tests` · `guard`(harness-guard) ·
`SAST (Semgrep OSS)`.

> `polyglot-ci` 는 워크플로 수준 `on.paths` 필터라 해당 경로를 안 바꾼 PR 에서 체크가 **아예 보고되지
> 않는다** → 필수로 걸면 영구 대기에 빠진다. 그래서 정보성으로만 둔다.

## 왜 Kaniko 가 아니라 BuildKit 인가

루트 `Dockerfile` 이 Gradle 의존성 캐시를 `RUN --mount=type=cache` 로 잡는다(`Dockerfile:34`, `:57`).
**Kaniko 는 이 문법을 지원하지 않는다.** BuildKit(또는 buildah)만 이 Dockerfile 을 수정 없이 빌드한다.

같은 이유로 데몬은 **상주형**이다. Job 마다 새로 띄우면 그 캐시가 매번 비어 의존성을 전량 다시 받는다.

## 이미지·태그 규칙 (양쪽이 반드시 일치)

태그는 `<branch>` · `<branch>-<sha7>` 이고 `latest` 는 기본 브랜치에서만 붙는다.
모듈 → 이미지 접미사 매핑의 정본은 `ci.yml` 의 `Compute image build matrix` 스텝이며,
`k8s/buildkit/build.sh` 의 `MAPPING` 이 그 사본이다.

**한쪽만 고치면 같은 서비스가 두 이미지로 갈라진다.** 배포 단위가 바뀌면 두 곳을 함께 바꾼다.

| 예시 | 이미지 |
| --- | --- |
| `order-service` | `ghcr.io/myoungsoo7/settlement` (접미사 없음) |
| `settlement-service` | `ghcr.io/myoungsoo7/settlement-settlement` |
| `frontend` | `ghcr.io/myoungsoo7/settlement-frontend` |

## ArgoCD 와의 접점

`settlement-prod` Application 의 image-updater 가 감시하는 것은 **`settlement`(order)와
`settlement-frontend` 둘뿐**이고 전략은 `newest-build` 다. 나머지 15개 서비스 이미지는 감시 대상이
아니며, 배포된 파드는 `main-<sha7>` 같은 불변 태그를 가리킨다.

→ 태그 규칙을 동일하게 맞췄으므로 빌드 주체가 바뀌어도 `helm-deploy` 는 수정할 것이 없다.
→ 다만 `settlement`·`settlement-frontend` 에 푸시하면 **운영에 자동 반영될 수 있다**. 그 둘을 k3s 에서
   구울 때는 어느 커밋인지 확인하고 민다.

## 옮기면 사라지는 것 (대체물 확인 후 끌 것)

1. **Trivy CRITICAL 게이트** (백엔드·프론트 각 1개) → `build.sh --scan` 이 대체한다
2. **`frontend-ghcr` 의 `needs: [frontend-ci, frontend-tests]`** → **대체물이 없다.** Actions 는
   "Vitest 빨간불이면 이미지도 안 나간다"를 강제하지만, k3s 수동 경로엔 그 연결이 없어 테스트가
   깨진 커밋도 그냥 구워진다. 감수할 수 없으면 `frontend-ghcr` 만 Actions 에 남긴다

## 실측 기록 (2026-08-14, 노드 `isagal`)

| 항목 | 값 |
| --- | --- |
| settlement-service 빌드 (캐시 없음) | Job 3분 36초 — 그중 Gradle 컴파일 1분 1초, 나머지는 베이스 이미지·의존성 |
| buildkitd 캐시 적재 | 2.55GB |
| 프론트엔드 빌드 | `vite build` 5.36초 (서브디렉터리 컨텍스트 `#<ref>:frontend`) |
| GHCR 푸시 | `:develop` + `:develop-<sha7>` 매니페스트 업로드 |
| Trivy CRITICAL | 0건 (런타임 베이스 alpine 3.23.5) |
| 참고: Actions 백엔드 잡 | 정상 55~70분 (timeout 90분) |

## 함정

- **빌드 컨텍스트는 git 원격이다.** 로컬 워킹트리가 아니라 `origin/<ref>` 의 커밋이 구워진다.
  `build.sh` 는 `git ls-remote` 로 원격 SHA 를 읽어 태그를 만들고 로컬 HEAD 와 다르면 경고한다.
- **sha7 은 YAML 에서 숫자로 읽힐 수 있다.** `23260e7` 같은 값이 지수 표기 실수로 파싱돼
  `cannot unmarshal number into ... labels of type string` 으로 apply 가 거부된 적이 있다. Job 템플릿의
  sha 라벨은 반드시 따옴표로 감싼다.
- **캐시 PVC 는 노드에 못 박히고 나중에 못 늘린다.** `local-path`(WaitForFirstConsumer)라 최초 스케줄된
  노드에 바인딩되며, 이 클러스터의 StorageClass 7종은 전부 `allowVolumeExpansion=false` 다.
- **`ARG MODULE` 이 비면 `gradle ::bootJar` 로 빈 세그먼트가 되어 빌드가 깨진다.** `build.sh` 가 모듈명을
  화이트리스트로 검증해 빈 값을 막는다.
- **`VITE_*` 는 번들에 굽히는 값이다.** 프론트 이미지는 백엔드와 달리 "한 번 굽고 환경별 재사용"이
  안 된다. 운영 값으로 배포하려면 `frontend-build-args` Secret 을 만들고 다시 구워야 한다.
- **Trivy 스캔은 푸시 뒤에 돈다.** CRITICAL 이 나와도 이미지는 이미 올라간 상태이며 롤백 판단은
  사람이 한다 — `ci.yml` 의 기존 동작과 같다.

## 관련 문서

- 운영 절차·설치·문제 해결 → [`k8s/buildkit/README.md`](../k8s/buildkit/README.md)
- 하네스 게이트 전체 목록 → [`HARNESS.md`](../HARNESS.md)
- 빌드 커맨드·인프라 → [`docs/DEVELOPMENT.md`](DEVELOPMENT.md)
