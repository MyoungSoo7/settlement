# DevOps — GitHub 관리부터 CI 보안·코드 품질, CD 자동 배포, 운영 안전망까지

> 이 문서는 settlement 프로젝트가 **소스 한 줄 변경부터 jen.lemuel.co.kr 실 운영 반영, 그리고 운영 결함 자동 감지까지** 어떤 게이트와 자동화를 거치는지 정리한다. 도메인 아키텍처는 [PORTFOLIO.md](../../PORTFOLIO.md) 와 [docs/ARCHITECTURE.md](ARCHITECTURE.md) 를 참고.

**Last updated:** 2026-05-15

---

## 0. 한눈에 — 코드 한 줄에서 운영까지

```
┌─────────────────────────────────────────────────────────────────────────┐
│ ① feat/fix/perf/* 브랜치 작업                                            │
│ ② develop 으로 PR ─ PR Review Bot + Codex 자동 코드리뷰 + paths-filter   │
│ ③ develop 머지                                                          │
│ ④ develop → main 릴리즈 PR ─ CI (백엔드 빌드/테스트, 프론트 빌드,         │
│    SonarCloud, JaCoCo, Snyk) + main Ruleset 보호                        │
│ ⑤ main 머지 (squash, ruleset 차단 통과)                                  │
│ ⑥ Backend/Frontend GHCR 이미지 빌드·푸시 (`main-<sha>` 태그)             │
│ ⑦ ArgoCD image-updater 가 새 태그 픽업 → settlement-prod Application     │
│    의 image spec 자동 갱신                                              │
│ ⑧ ArgoCD 가 helm-deploy 리포의 values 와 sync → K3s 노드에 RollingUpdate │
│ ⑨ ② post-deploy E2E (Playwright) 가 운영 jen.lemuel.co.kr 직접 검증     │
│ ⑩ ELK 5분 스파이크 알림 + 텔레그램 봇 채널 운영자 호출                    │
└─────────────────────────────────────────────────────────────────────────┘
```

각 단계의 게이트와 자동화 구성요소는 아래에서 단계별로 본다.

---

## 1. GitHub — 브랜치 전략과 보호 규칙

### 1.1 브랜치 책임 분리

| 브랜치 | 역할 | 보호 |
|---|---|---|
| `main` | **배포 브랜치**. 머지 = jen.lemuel.co.kr 실 운영 반영 | Ruleset 13251491, 직접 푸시·force-push·삭제 금지, squash 머지 강제 |
| `develop` | CI/Snyk/소스 안전성 검사 통합 브랜치. 작업 브랜치들이 이리로 모임 | PR Review Bot 자동 실행 |
| `feat/*` `fix/*` `perf/*` | 작업 브랜치. develop 으로 PR | — |

원칙: **작업 브랜치 → develop PR → 통과 → main 릴리즈 PR → 운영 반영**.
hotfix 의 경우만 작업 브랜치에서 main 으로 직접 PR 도 허용 (오늘 #75 frontend nginx upstream 케이스).

### 1.2 main Ruleset 의 정확한 차단 항목

```yaml
ruleset_id: 13251491
enforcement: active
rules:
  - deletion: 차단              # 브랜치 삭제 금지
  - non_fast_forward: 차단      # force push 금지
  - pull_request:
      allowed_merge_methods: [squash]      # squash 머지만 허용
      required_approving_review_count: 0   # 셀프 머지 가능 (1인 운영)
      required_review_thread_resolution: true   # 리뷰 스레드 미해결 시 차단
  - required_status_checks:
      required: [Detect changed paths]      # 필수 체크 — paths-filter 통과
bypass_actors: []   # 관리자도 우회 불가
```

이 룰셋 덕에 **머지 직전에 코드리뷰 봇이 단 thread 가 미해결이면 빨간 표시** 가 떠 머지 자체가 GraphQL API 레벨에서 차단된다.
실제로 어제 PR #78 머지가 Codex 리뷰봇의 unresolved thread 2 건 때문에 막혀, 각 코멘트에 증거 회신 후 명시적으로 resolveReviewThread mutation 으로 풀어야 머지 가능했다.

### 1.3 자동 리뷰 봇 — PR Review Bot + Codex Connector

PR 이 열리면 두 봇이 동시에 동작한다.

**Lightweight PR Review** (`.github/workflows/pr-review.yml`)
- 작은 diff 에 즉시 코멘트.
- 메인 CI 와 별개라 10초 안에 결과 회신.
- main / develop 양쪽 PR 에 모두 적용.

**Codex Connector** (외부 봇)
- `P1` / `P2` 우선순위 라벨이 붙은 자동 리뷰.
- 정확도는 70% 정도 — 오늘만 잘못된 P1 (legacy `k8s/base/*.yaml` 의 `lemuel-service` 가 실제 Service 라고 오판) 이 두 번 떴고, P2 (post-deploy-smoke 가 backend-ghcr 를 needs 안 함) 는 정확했다.
- 양쪽 다 unresolved 면 머지 차단이므로, **잘못된 P1 도 증거 댓글 + resolve mutation 으로 명시적 거부** 가 필요. 이 워크플로우 자체가 1인 운영자에게 강제되는 코드리뷰 의식 — 사람 리뷰어가 없어도 봇 의견을 매번 평가하게 된다.

### 1.4 release PR 패턴

```bash
gh pr create --base main --head develop \
  --title "release: develop → main — <한 줄 요약>" \
  --body "..."
gh pr merge <num> --squash
```

이 워크플로우는 별도 매크로/자동화가 아니다. 오히려 **사람이 의식적으로 \"이 변경분이 운영에 가도 되나\"** 라는 한 번의 게이트를 거치게 만든다.
하루에 5번 hotfix 가 나간 2026-05-14 같은 날엔 develop 의 큰 변경분이 한 번에 main 으로 들어가는 위험을 피하기 위해, **hotfix 만 직접 main 으로 PR** 하는 변형도 허용된다.

---

## 2. CI — 보안과 코드 품질을 동시에 강제하는 다층 체인

### 2.1 paths-filter 로 backend/frontend 분리

`.github/workflows/ci.yml` 의 첫 job 이 `dorny/paths-filter@v3` 로 diff 영역을 분류한다.

```yaml
filters:
  frontend:
    - 'frontend/**'
  backend:
    - '**'
    - '!frontend/**'
```

- frontend 만 변경되면 backend-ci 가 SKIPPED → CI 시간 절반.
- 그러나 항상 트리거되는 \"**Detect changed paths**\" 자체가 main Ruleset 의 단 하나 필수 체크이므로, 머지 게이트는 그대로 유지된다 (다른 체크들이 skipped 여도 머지 가능).

### 2.2 Backend CI — Spring Boot + 회계 도메인의 다중 게이트

```
backend-ci
├── ./gradlew clean build (test 분리)
├── Postgres 17 + Elasticsearch 8.11 service container 로 실 통합 테스트
├── JaCoCo 커버리지 측정
│   ├── LINE 50% 전체 최소
│   └── INSTRUCTION 80% (payment/order/product/cart/shipping/settlement/ledger 도메인)
├── PR 코멘트에 자동 커버리지 리포트 (madrapps/jacoco-report@v1.6.1)
├── SonarCloud (main 푸시 시) — 정적 분석 + Quality Gate
└── Snyk gradle-jdk21 (severity high+) → GitHub Code Scanning 으로 SARIF 업로드
```

핵심 design choice:
- **persistence/config/util 은 단위 커버리지 제외** — Testcontainers IT 가 따로 검증.
- **회계 핵심 도메인은 INSTRUCTION 80%** — 차변/대변 균형 등 회계 원칙은 단위테스트로 빈틈없이 봉인.
- **SonarCloud Quality Gate 는 PR 에서는 우회** (continue-on-error) — main 푸시 후 별도 모니터링. PR 속도 우선.

### 2.3 Frontend CI — TypeScript strict + 보안

```
frontend-ci
├── npm ci (lockfile strict)
├── tsc --noEmit -p tsconfig.app.json     # production 코드만
├── eslint . --max-warnings 0 (continue-on-error: true)
├── npm run build (Vite production bundle, code-splitting)
├── frontend-dist 아티팩트 7 일 보존
└── Snyk node + SARIF
```

오늘 학습: **로컬 npm 11 vs CI npm 10 (Node 20 번들) lockfile 호환성** 이슈로 PR #79·#80 두 단계 hotfix 가 필요했다. `npx -y npm@10 install` 로 lockfile 을 CI 와 동일 npm 으로 재생성하지 않으면 `EBADPLATFORM` (netbsd-arm64 on linux-x64) 같은 platform-specific optional deps 경합이 터진다. **follow-up: Node 22 LTS 업그레이드 + `.nvmrc` 추가** 로 양쪽 통일.

### 2.4 GHCR 이미지 빌드·푸시

```
backend-ghcr / frontend-ghcr
├── docker/setup-buildx-action@v3
├── GHCR 로그인 (GITHUB_TOKEN)
├── docker/metadata-action@v5 — 태그 자동 생성
│   ├── type=ref,event=branch    → main, develop
│   ├── type=sha,prefix={{branch}}-  → main-c2e06ad
│   └── type=raw,value=latest,enable={{is_default_branch}}
└── docker/build-push-action@v5 — multi-stage build + GHA 캐시
```

태그 전략의 핵심: **`main-<short-sha>`** 가 ArgoCD image-updater 의 `newest-build` 전략에 정확히 매칭되도록 설계됨. 오늘 PR #82 가 머지되자마자 새 태그 `main-c2e06ad` 가 GHCR 에 push 되고, 약 2 분 뒤 image-updater 가 픽업했다.

### 2.5 자동 코드리뷰 — Lightweight + Codex

PR 트리거 시 `.github/workflows/pr-review.yml` 의 \"Lightweight PR Review\" 가 10초 안에 끝나는 가벼운 봇이 코멘트를 남긴다.
이와 별개로 외부 Codex Connector 봇이 diff 전체를 읽고 P1/P2 라벨로 의견을 단다.

이 두 채널의 조합 덕에 **혼자 개발해도 매번 \"이 변경 정말 안전한가\" 라는 외부 시선** 을 강제로 거친다.

---

## 3. CD — ArgoCD GitOps 와 헬름 차트 두 리포 구조

### 3.1 GitOps 두 리포 — settlement / helm-deploy

```
github.com/MyoungSoo7/settlement     ← 애플리케이션 코드 (이 리포)
github.com/MyoungSoo7/helm-deploy    ← 배포 매니페스트 + values
```

settlement 의 CI 가 GHCR 에 `main-<sha>` 이미지를 push 하면 끝이다. **그 이후 어떤 환경에 어떤 태그를 배포할지** 는 helm-deploy 가 단독 책임진다. 이렇게 분리한 이유:

- 애플리케이션 변경과 인프라 변경의 차이가 명확.
- 이미지 빌드와 배포 결정이 분리되어 같은 이미지를 prod / staging 에 임의로 매핑 가능.
- helm-deploy 에서 image tag 수동 pin 으로 staging 만 옛 빌드 유지하는 시나리오가 자연스럽게 표현됨 (오늘 5월 15일 staging 사건이 정확히 이 케이스).

### 3.2 K3s 클러스터 — 5 노드

내부망 192.168.219.0/24 의 5 대 노드 — `lemuel`/`louise`/`david`/`solomon`/`ilwon`.
podAntiAffinity 로 settlement-app 의 replica 들이 같은 노드에 몰리지 않도록 권장 (preferredDuringSchedulingIgnoredDuringExecution).

### 3.3 settlement-prod ArgoCD Application — image-updater 자동 픽업

```yaml
# 핵심 annotations (이 리포가 아닌 helm-deploy 측에 있음)
argocd-image-updater.argoproj.io/image-list: |
  backend=ghcr.io/myoungsoo7/settlement
  frontend=ghcr.io/myoungsoo7/settlement-frontend
argocd-image-updater.argoproj.io/backend.update-strategy: newest-build
argocd-image-updater.argoproj.io/backend.helm.image-name: app.image.repository
argocd-image-updater.argoproj.io/backend.helm.image-tag: app.image.tag
argocd-image-updater.argoproj.io/write-back-method: argocd
```

흐름:
1. **2분 주기** 로 image-updater 가 GHCR 의 latest manifest 를 polling.
2. `newest-build` 전략으로 가장 최근에 push 된 `main-*` 태그 선택.
3. ArgoCD Application 의 helm values 를 in-cluster 로 patch (`write-back-method: argocd`).
4. ArgoCD 가 spec drift 감지 → Sync.
5. Helm chart 가 새 image tag 로 Deployment 재생성 → RollingUpdate (maxSurge=1, maxUnavailable=0).
6. readinessProbe 통과한 새 pod 이 살아나면 옛 pod 종료.

총 소요: image push 부터 pod 갱신까지 **약 3 ~ 5 분**.

### 3.4 settlement-staging — 의도적으로 자동 픽업 OFF

staging Application 에는 **image-updater annotations 가 없다**. `values-staging.yaml` 의 `image.tag` 가 직접 명시되어 있고, 변경 시 helm-deploy 리포에 PR 또는 커밋이 필요하다.

```yaml
# helm-deploy/charts/settlement/values-staging.yaml
app:
  image:
    repository: settlement
    tag: main-c2e06ad   # ← 손으로 갱신
```

왜 자동 안 하는가:
- **staging 은 \"검증을 통과한 후보 이미지를 일정 기간 유지\" 하는 의미** 가 있다. prod 가 매번 newest 로 가는 것과 대비.
- 단점: hotfix 가 prod 에만 적용되고 staging 은 옛 결함 상태로 잔존 → 오늘 ELK 알림이 prod 0건 / staging 149건 으로 비대칭하게 떨어진 이유. memory `operational_safety_plan` 의 (c) 3순위로 \"develop 자동 배포\" 가 잔존 항목이다.

### 3.5 데이터 영속 — namespace 분리

| namespace | DB | ES | 비고 |
|---|---|---|---|
| `settlement-prod` | `jen-postgres` (별도 namespace `jen-prod`) | `settlement-elasticsearch` | 운영 DB. 변경 시 영향 큼 |
| `settlement-staging` | `settlement-staging-postgres` | `settlement-staging-elasticsearch` | 자체 PG (운영 jen-postgres 와 격리) |

이 분리는 staging 의 자동 배포 정책 차이와 함께 \"prod 실험 차단\" 의 핵심 안전망. 마이그레이션(Flyway V1~V46) 이 잘못 들어가도 prod DB 가 즉시 망가지지는 않는다.

---

## 4. 운영 안전망 — 단위테스트가 못 잡는 결함을 다층으로

2026-05-14 ~ 15 두 날에 6 번의 hotfix 와 한 번의 ERROR 폭주가 있었다. 모두 **\"코드 레이어 단위테스트 통과 → 컨테이너/네트워크/스케줄러 통합 레이어 실패\"** 의 동일 패턴.

| 사건 | 원인 | 단위테스트가 못 잡은 이유 | 해결 PR |
|---|---|---|---|
| nginx upstream `lemuel-service` | 실제 K8s Service 는 `settlement-app`. 호스트명 typo | nginx.conf 는 코드가 아니라 컨테이너 부팅 시 DNS 해석 시점 | #70, #75 |
| `/auth/dev/**` permitAll 누락 | Spring Security 필터 체인이 데모 자동로그인 차단 | 단위테스트 환경엔 시큐리티 필터 비활성 | #71 |
| CORS env 변수명 불일치 | application.yml 의 `cors.origins` 가 다른 환경변수에서 주입 | 환경변수 주입은 컨테이너 런타임 | #72 |
| `VITE_API_BASE_URL` localhost | dev 빌드의 baseURL 이 운영 이미지에 박혀 들어감 | Vite 빌드 시점 환경변수 | #73 |
| frontend nginx `lemuel-service` (재발) | PR #70 이 *루트* nginx.conf 만 정정, frontend 이미지가 쓰는 `frontend/nginx.conf` 미수정 | 동일 패턴이 두 파일에 중복 | #75 |
| `@Query(:status)` @Param 누락 | named param 이 javac -parameters 없이는 매칭 안 됨, OutboxScheduler 2초 polling 마다 ERROR | 단위테스트는 빈 Outbox 라 쿼리 실행 안 됨 | #82 |

이 패턴을 사전 차단하려고 도입한 안전망:

### 4.1 Playwright E2E + post-deploy smoke (PR #77, #83)

```
frontend/
├── e2e/
│   ├── auto-login.spec.ts   # USER/MANAGER/ADMIN/GUEST 4종
│   └── smoke.spec.ts        # /, /actuator/health, /auth/dev/auto-login
└── playwright.config.ts     # baseURL=PLAYWRIGHT_BASE_URL (기본 jen)
```

**CI 통합**:
```yaml
post-deploy-smoke:
  needs: [frontend-ghcr, backend-ghcr]   # PR #83 에서 backend-ghcr 도 추가
  if: |
    github.event_name == 'push'
    && github.ref == 'refs/heads/main'
    && needs.frontend-ghcr.result != 'failure'
    && needs.backend-ghcr.result != 'failure'
  steps:
    - npm ci + playwright install chromium
    - sleep 180   # ArgoCD 폴링 + rollout 여유
    - npm run e2e   # against jen.lemuel.co.kr
    - 실패 시 playwright-report 14일 보존
```

이 안전망의 직접 ROI:
- #70 / #75 (nginx upstream) → `/actuator/health` smoke 가 즉시 502 잡음.
- #71 (auth/dev permitAll) → 자동로그인 4종 클릭 시 401 잡음.
- #72 (CORS) → fetch 응답 코드로 즉시 잡음.
- #73 (baseURL) → fetch 가 잘못된 호스트로 가서 즉시 잡음.

\"main 머지 → 약 3분 30초 안에 GitHub Actions 가 fail-loud\" 라는 SLA 를 확보.

### 4.2 PR #82 — Outbox @Param 누락 hotfix

```java
// before: scheduler 2초마다 1건씩 ERROR 폭주
@Query("SELECT e FROM OutboxEventJpaEntity e WHERE e.status = :status ORDER BY e.createdAt ASC")
List<OutboxEventJpaEntity> findByStatusOrderByCreatedAtAsc(OutboxEventStatus status, Pageable pageable);

// after: 명시 바인딩
List<OutboxEventJpaEntity> findByStatusOrderByCreatedAtAsc(@Param("status") OutboxEventStatus status, Pageable pageable);
```

같은 패턴이 `PaymentJpaRepository.findCapturedPaymentsBetween` 에도 잠재.
**follow-up**: build.gradle.kts 에 `tasks.withType<JavaCompile> { options.compilerArgs.add("-parameters") }` 를 추가하면 named param 자동 매칭으로 이 클래스 결함 자체가 사라진다. ArchUnit 으로 @Query named param ↔ @Param 매칭 정적 검증 규칙도 가능.

### 4.3 ELK 스파이크 알림 — 5분 윈도우

helm-deploy 리포가 별도로 CronJob 을 띄워 5분마다 Elasticsearch 에 ERROR 로그 카운트를 질의, 임계 (`threshold 20`) 초과 시 텔레그램 봇 채널로 알림.
오늘 (2026-05-15) 두 번의 알림이 정확히 이 channel 로 와서 즉시 PR #82 가 진행되었고, **알림 → 운영 회복까지 25 분** 만에 끝났다.

### 4.4 자동 모니터링과 사람 (텔레그램 봇 채널)

이 모든 게이트와 알림이 **하나의 텔레그램 채널로 합류** 한다. 운영자는 휴대폰에서 알림 한 번에:
- ELK ERROR 스파이크
- Argo Application 상태 변화 (잔존 follow-up)
- 502 등 HTTP 비정상
- main 배포 완료 / 실패

까지 받게 된다. 1인 운영 환경에서 \"내가 자고 있는 새벽에 운영이 뭐가 났는지 모른다\" 가 가장 큰 리스크인데, 이걸 채널 단일 진입점으로 묶었다.

---

## 5. 잔존 follow-up — operational_safety_plan

memory 의 6 단계 안전망 중 1순위 (Playwright E2E + post-deploy smoke) 만 완료. 나머지:

| 우선순위 | 항목 | 예상 효과 |
|---|---|---|
| (a) ✅ | Playwright E2E + post-deploy smoke | 4 종 통합 결함 즉시 감지 |
| (b) | post-deploy smoke 텔레그램 알림 통합 | 현재는 GitHub Actions 가 fail-loud, 채널 알림은 별도. 통합 시 사람이 GitHub 화면 안 봐도 됨 |
| (c) | Staging 자동 배포 (develop 푸시 시) | 오늘 ERROR 폭주가 prod / staging 비대칭으로 떨어진 원인 해소 |
| (d) | 헬스 모니터링 + 텔레그램 (5분 synthetic check) | CrashLoopBackOff·502 가 6분 안에 알림. 오늘 6번 hotfix 중 #75 (frontend nginx upstream) 가 정확히 이 케이스 |
| (e) | Config drift detection — nginx upstream ↔ helm Service.name 정적 매칭 | #70 / #75 의 root cause. 빌드 단계 차단 가능 |
| (f) | 자동 롤백 | 운영 헬스 X 분 fail → 직전 이미지 태그로 ArgoCD revert |
| (g) | javac `-parameters` + ArchUnit @Query 검증 | #82 의 root cause. 빌드 단계 차단 |

**시급도 ↑**: (d) 헬스 모니터링은 오늘 ELK 알림으로 보완되었지만, ERROR 스파이크가 안 뜨는 결함 (예: 502, 무한 retry) 은 여전히 사용자가 먼저 발견할 수 있다.

---

## 6. 실제 incident 기록 — 2026-05-14 ~ 15

DevOps 자랑은 실 사고 회복 기록으로만 검증된다. 두 날의 timeline:

### 2026-05-14

| 시각 (KST) | 사건 | PR | 회복 |
|---|---|---|---|
| 종일 | feat/auto-login 작업 중 5번 연속 hotfix | #68 ~ #73 | 매번 약 5~10분 |
| 23:50 | \"운영 안전성 개선 계획\" 작성, Playwright 우선순위 확정 | (memory) | — |

### 2026-05-15

| 시각 (KST) | 사건 | PR | 회복 |
|---|---|---|---|
| 00:23 | jen.lemuel.co.kr 502 발견 (PR #70 의 미완 수정) | #75 | 00:34 (11분) |
| 00:38 | Playwright E2E + post-deploy smoke 도입 작업 시작 | #77 | self-test 통과 |
| 02:38 ~ 02:42 | lockfile npm 11 ↔ 10 호환 hotfix 2단계 | #79, #80 | — |
| 02:42 | Playwright 안전망 main 머지 (self-test SUCCESS) | #78 | — |
| 04:40 | ELK 1차 알림 — settlement-prod 150건 / staging 149건 | — | — |
| 04:45 | ELK 2차 알림 | — | — |
| 04:47 | 원인 추적 — OutboxScheduler 2초 polling, @Param 누락 | #81 | develop 머지 |
| 04:51 | main 머지 (Codex 리뷰봇 P1 잘못된 의견 1건 + P2 정확한 의견 1건 처리) | #82 | — |
| 04:53 | post-deploy-smoke 가 backend-ghcr 도 needs 하도록 보강 | #83 | develop 머지 |
| 04:55 | prod settlement-app 새 pod main-c2e06ad rollout 완료, ERROR 0 | — | — |
| 05:05 | staging 도 image tag 수동 갱신 (helm-deploy), rollout | (helm-deploy commit) | — |
| 05:06 | prod / staging 모두 60초 ERROR 0, 안정화 확인 | — | **사이클 완료** |

**총 incident-to-resolution**: 25 분 (04:40 ~ 05:06).
한 번의 알림으로 코드 변경 + 두 환경 배포까지 완결한 흐름이다.

---

## 7. 1인 운영 + 다층 방어 — 설계 철학

### 7.1 \"단위테스트는 충분조건이 아니다\"

오늘 6 번의 hotfix 모두 백엔드/프론트엔드 단위테스트는 그린이었다. 통합 결함은 본질적으로:

- 컨테이너 부팅 후 DNS / 환경변수 / 파일시스템 매핑 시점
- 다른 서비스와의 네트워크 경계 (CORS, JWT, nginx 프록시)
- 스케줄러 / 백그라운드 작업의 시간 누적
- 빌드 시점에 결정되는 클라이언트 환경변수

에서 발생하므로, **컨테이너 빌드 후, 운영 환경 안에서, 시간 흐름과 함께 검증** 해야 잡힌다. 이게 post-deploy E2E + ELK 알림이 다층으로 필요한 이유.

### 7.2 \"잘못된 봇 의견도 명시적으로 거부\"

Codex 리뷰봇은 오늘 두 번이나 동일 패턴의 잘못된 P1 의견 (`k8s/base/*.yaml` 의 legacy lemuel-service 가 실제 Service 라고 오판) 을 달았다. main Ruleset 의 `required_review_thread_resolution: true` 가 이 잘못된 의견에도 \"증거 회신 + 명시적 resolve\" 를 강제했다.

**부작용 → 안전망**: 매번 봇 의견을 평가하게 되어 진짜 P1 (lockfile 호환성, backend-ghcr race) 을 놓치지 않고 잡았다.

### 7.3 \"git log 가 진실 — 메모리/문서는 보조\"

이번 25분 incident 동안 모든 결정은 PR diff 와 `gh pr view --json statusCheckRollup`, `kubectl logs ... --since=60s` 의 raw 출력에서 끌어왔다.
memory 에는 \"왜 이렇게 결정했는지\" (예: staging 이 자동 배포 안 되는 이유) 만 적고, 현재 코드/이미지/pod 가 어떤 상태인지는 항상 실시간 명령으로 확인한다. 이게 잘못된 가정으로 hotfix 가 다른 결함을 만드는 패턴 (#70 → #75 같은 재발) 을 줄인다.

---

## 8. 다음 단계 — \"E2E 자동화 궁극 목표\"

memory `settlement_e2e_automation_goal` 의 그림:

```
텔레그램 메시지 \"X 기능 추가해\"
  ↓ (1) 봇이 코드 작성 + PR
  ↓ (2) CI 통과
  ↓ (3) develop → main 자동 PR + 자동 머지
  ↓ (4) ArgoCD 자동 배포
  ↓ (5) K3s 노드 rollout
  ↓ (6) post-deploy E2E
  ↓ (7) 텔레그램에 \"운영 적용 완료\" 보고
사람은 첫 메시지와 마지막 보고만 본다
```

오늘 25분 사이클이 이미 (2) ~ (7) 을 자동/반자동으로 묶었다. (1) 의 \"봇이 코드 작성\" 부분이 본 텔레그램 봇 채널 자체이며, 1인 운영의 한계를 자동 위임으로 보강하는 portfolio 의 핵심.

---

## 부록 — 참고 파일

- [.github/workflows/ci.yml](../../.github/workflows/ci.yml) — CI 전체 정의
- [.github/workflows/pr-review.yml](../../.github/workflows/pr-review.yml) — Lightweight PR Review
- `helm-deploy/charts/settlement/values-prod.yaml` — prod 배포 values
- `helm-deploy/charts/settlement/values-staging.yaml` — staging 배포 values (수동 tag)
- [frontend/playwright.config.ts](../../frontend/playwright.config.ts) — Playwright 환경 분기
- [frontend/e2e/](../frontend/e2e/) — 4 종 자동로그인 + smoke 시나리오
- [docs/DEPLOYMENT.md](DEPLOYMENT.md) — 배포 상세
- [docs/INFRASTRUCTURE.md](INFRASTRUCTURE.md) — K3s / ArgoCD / 네트워크
- [docs/CI_CONFIGURATION.md](CI_CONFIGURATION.md) — CI 세부 설정 변천사
