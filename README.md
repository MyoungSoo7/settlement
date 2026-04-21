# Lemuel - 전자상거래 주문/결제/정산 통합 시스템

주문, 결제, 정산, 환불을 처리하는 이커머스 통합 플랫폼 (헥사고날 아키텍처)

## 프로젝트 소개

Lemuel은 Spring Boot 기반의 전자상거래 백엔드 시스템입니다. Toss Payments PG 연동, Spring Batch 기반 자동 정산, Elasticsearch 검색, 환불 동시성 제어(Pessimistic Lock + 멱등성 키) 등 실무 수준의 결제/정산 도메인을 구현했습니다.

---

## 기술 스택

| 구분 | 기술 | 버전 |
|------|------|------|
| Backend | Java + Spring Boot + JPA | 25 / 4.0.4 |
| Frontend | React + TypeScript + Vite | 18 / 5 / 5 |
| Database | PostgreSQL | 17 |
| 검색 엔진 | Elasticsearch (Nori 한글 분석기) | 8.x |
| 결제 | Toss Payments PG | - |
| 배치 | Spring Batch (CronJob) | - |
| 캐시 | Caffeine Cache | - |
| PDF | iText PDF | - |
| 모니터링 | Micrometer + Prometheus + Grafana | - |
| 마이그레이션 | Flyway (V1~V22) | - |
| 코드 품질 | SonarCloud + Snyk + JaCoCo (70%+) | - |
| API 문서 | SpringDoc OpenAPI (Swagger) | 2.8.0 |
| Infra | Docker Compose (dev) / Kubernetes (prod) | - |
| CI/CD | GitHub Actions (GHCR 배포) | - |

---

## 주요 기능

| 도메인 | 기능 |
|--------|------|
| **User** | 회원가입, 로그인, JWT 인증, 비밀번호 재설정 |
| **Product** | 상품 CRUD, 상태 관리, 이미지 업로드, 검색 |
| **Order** | 주문 생성, 상태 변경, 취소/환불 |
| **Payment** | 승인/매입/취소, PG 연동, 부분 환불 |
| **Settlement** | 자동 정산 생성/확정, 환불 조정, ES 검색 |
| **Category** | 다계층 이커머스 카테고리 (슬러그 기반) |
| **Coupon** | 쿠폰 생성/조회/사용/만료 |
| **Review** | 상품 리뷰 CRUD, 평점 |

### 정산 플로우 (핵심 도메인)

```
결제 완료 (CAPTURED)
    -> Settlement 생성 (REQUESTED)
        -> 배치 새벽 02:00 -> PROCESSING
            -> 배치 새벽 03:00 -> DONE / CONFIRMED
                -> 환불 발생 시 -> SettlementAdjustment 생성
                -> Elasticsearch 인덱싱
```

---

## 빠른 시작

### 사전 요구사항

- JDK 25+
- Docker & Docker Compose

### 실행

```bash
# Docker Compose로 전체 실행
docker compose up -d

# 또는 개별 실행
docker compose up -d postgres elasticsearch
./gradlew bootRun --args='--spring.profiles.active=local'
```

### 테스트

```bash
./gradlew test
```

---

## 프로젝트 구조

```
settlement/
├── src/main/java/github/lms/lemuel/  # Backend (Hexagonal Architecture)
│   ├── user/                          # 인증/인가
│   ├── product/                       # 상품 CRUD, 이미지, 검색
│   ├── order/                         # 주문 생성/상태 변경
│   ├── payment/                       # PG 연동, 결제/환불
│   ├── settlement/                    # 정산 자동화, ES 검색
│   ├── category/                      # 다계층 카테고리
│   ├── coupon/                        # 쿠폰 발급/사용
│   ├── review/                        # 상품 리뷰
│   └── common/                        # JWT, Security, Batch 설정
├── src/main/resources/db/migration/   # Flyway V1~V22
├── frontend/                          # React + Vite 프론트엔드
├── k8s/                               # Kubernetes 설정
├── monitoring/                        # Prometheus / Grafana
├── .github/workflows/                 # CI/CD
├── docker-compose.yml
└── build.gradle.kts
```

---

## CI/CD

```
push / PR 트리거
  -> backend-ci (PostgreSQL + ES 서비스, Gradle 빌드/테스트, JaCoCo, SonarCloud, Snyk)
  -> backend-ghcr (Docker -> GHCR Push)
  -> frontend-ci (TypeScript 체크, ESLint, Vite 빌드, Snyk)
  -> frontend-ghcr (Docker -> GHCR Push)
```

---

## 보안

| 항목 | 구현 |
|------|------|
| JWT 인증 (HS256) | O |
| BCrypt (cost=12) | O |
| CORS 환경변수 설정 | O |
| Rate Limiting (nginx) | O |
| Actuator 인증 필수 | O |
| 환불 멱등성 (Idempotency-Key) | O |
| Pessimistic Lock (환불 동시성) | O |

---

## 문서

| 문서 | 경로 |
|------|------|
| Claude Code 컨텍스트 | [`CLAUDE.md`](./CLAUDE.md) |
| CI/CD 워크플로우 | [`.github/workflows/ci.yml`](.github/workflows/ci.yml) |
| Kubernetes 설정 | [`k8s/`](./k8s/) |
| Flyway 마이그레이션 | [`src/main/resources/db/migration/`](src/main/resources/db/migration/) |

---

## 라이선스

이 프로젝트는 **AGPL-3.0** 라이선스를 따릅니다 (iText 8 의존성).
