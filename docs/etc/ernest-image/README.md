# Cluster Diagram Generator

삼각기둥·원기둥·육각기둥이 클러스터를 이루는 팔란티어 스타일의 소개 이미지를
**AI 이미지 생성 없이, 순수 코드(Python + matplotlib)** 로 그리는 프로젝트입니다.

> 어니스트AI 사전 질문지 **문제 3. 모호한 요구사항을 코드로 정의하기** 제출물입니다.

![palantir](out/palantir.png)

---

## 1. 문제를 어떻게 정의했는가 (Unknown → Known)

"예시와 유사한 느낌의 이미지"라는 모호한 요구를 아래 세 단계로 명확화했습니다.

| 단계 | Unknown | 내가 정의한 Known |
|---|---|---|
| ① 그림 | "유사한 느낌"이 뭔가? | 흰 면 + 얇은 외곽선의 기둥 라인아트, 옅은 파랑 윗면 포인트, 점선 연결, `← LABEL` 주석, 옅은 그라데이션 배경 |
| ② 도형 | 삼각/원/육각기둥만? | **임의의 볼록 N각기둥으로 일반화.** 윗면 다각형만 정의하면 몸통은 convex hull 로 자동 계산 → 사각·오각기둥은 물론 `prism:8` 같은 미등록 도형도 즉시 지원 |
| ③ 클러스터 | 클러스터란 뭔가? | "그림"이 아니라 **의미 구조**로 정의. 클러스터 = 의미적으로 묶이는 구성요소 집합. 따라서 입력은 그림 좌표가 아니라 **시스템 기술(JSON)** 이고, 렌더러는 그것을 번역할 뿐 |

③이 이 프로젝트의 핵심입니다. 같은 엔진에 설정만 바꿔 넣으면
쿠버네티스 클러스터도, 회사의 결제·정산 시스템도 같은 스타일로 그려집니다.

| 쿠버네티스 (`configs/kubernetes.json`) | 회사 시스템 (`configs/company_system.json`) |
|---|---|
| ![k8s](out/kubernetes.png) | ![company](out/company_system.png) |

---

## 2. 실행 방법

```bash
./run.sh                            # 3개 프리셋 전부 생성 → out/*.png
./run.sh configs/kubernetes.json    # 특정 설정만 렌더링

# 직접 실행
python3 -m src.main --config configs/palantir.json --output out/palantir.png
python3 -m src.main --list-shapes   # 지원 도형 목록
python3 -m src.main -c configs/palantir.json -o out/v2.png --seed 99  # 배치 변형 탐색
```

의존성: Python 3.10+, `matplotlib`, `numpy` (run.sh 가 없으면 자동 설치)

---

## 3. 구조

```
├── run.sh                  # 실행 스크립트 (필수 제출물)
├── configs/                # "무엇을 그릴 것인가" = 도메인 모델
│   ├── palantir.json       #   과제 예시 재현
│   ├── kubernetes.json     #   의미 입력 예시 1
│   └── company_system.json #   의미 입력 예시 2 (결제·정산 도메인)
├── src/
│   ├── shapes.py           # Shape Registry + 기둥 렌더링 (convex hull 실루엣)
│   ├── layout.py           # 클러스터 배치 (시드 고정 가우시안 산포 + 겹침 거부)
│   ├── renderer.py         # 씬 합성 (깊이 정렬, 점선 연결, 주석)
│   └── main.py             # CLI
└── out/                    # 최종 이미지 (필수 제출물)
```

### 설계 포인트

**도형 확장성 — Shape Registry**
```python
SHAPE_REGISTRY = {
    "triangle": (3, False), "square": (4, False), "pentagon": (5, False),
    "hexagon": (6, False), "cylinder": (64, True),   # 새 도형 = 한 줄 추가
}
```
원기둥을 특수 케이스로 만들지 않고 "꼭짓점이 매우 많은 다각형 + 내부 모서리선
생략(smooth)"으로 통일했습니다. 몸통 실루엣은 위·아래 꼭짓점의 convex hull 이므로
어떤 볼록 다각형이든 같은 코드로 그려집니다.

**의미 확장성 — 설정이 곧 도메인 모델**
```json
{ "id": "control-plane",
  "center": [0.26, 0.68],
  "members": [
    {"shape": "hexagon",  "count": 1, "accent": 1.0, "label": "API SERVER"},
    {"shape": "pentagon", "count": 3, "label": "ETCD"} ] }
```
클러스터·구성요소·연결 관계만 기술하면 배치(산포/겹침 방지/깊이 정렬)와
스타일은 엔진이 책임집니다. 새로운 시스템을 그리는 데 코드 수정이 필요 없습니다.

**재현성** — 배치는 `seed` 로 고정되어 같은 설정은 항상 같은 그림을 만듭니다.
`--seed` 로 마음에 드는 구도가 나올 때까지 변형을 탐색할 수 있습니다.

---

## 4. 검증

- `run.sh` 클린 환경 실행 → 3개 PNG 생성 확인
- 경계 케이스: `prism:3`(=triangle)·`prism:12`·미등록 도형 이름(명확한 에러 메시지),
  과밀 설정(rejection 400회 초과 시 마지막 후보 채택으로 무한루프 방지)
- 시각 검수: 라벨 가독성(흰색 헤일로), 윗면 포인트가 다각형 내접원을 벗어나지 않음,
  깊이 정렬(아래쪽 도형이 앞에 오는지)
