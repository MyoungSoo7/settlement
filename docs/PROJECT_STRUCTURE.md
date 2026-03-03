# Lemuel 프로젝트 구조 분석

> v0.2.0 기준 (2026-03-03 분석)

---

## 1. 프로젝트 개요

**Lemuel** — 전자상거래 주문·결제·정산 통합 시스템

| 항목 | 내용 |
|------|------|
| 구조 | 모노레포 (백엔드 + 프론트엔드) |
| 버전 | v0.2.0 |
| 백엔드 | Spring Boot 3.5.10, Java 21 |
| 프론트엔드 | React 18, TypeScript, Vite |
| 아키텍처 | 헥사고날 (Ports & Adapters) |

```
lemuel/
├── src/main/java/          # 백엔드
├── src/main/resources/     # 설정, Flyway 마이그레이션
├── frontend/               # 프론트엔드
├── k8s/                    # Kubernetes 설정
├── monitoring/             # Prometheus / Grafana
├── docs/                   # 문서
├── .github/workflows/      # CI/CD
└── build.gradle.kts
```

---

## 2. 백엔드

### 2.1 도메인 패키지 구조

```
github/lms/lemuel/
├── user/          # 인증/인가, JWT, 비밀번호 재설정
├── product/       # 상품 CRUD, 이미지, 검색              (64개 파일)
├── order/         # 주문 생성/상태 변경/취소/환불         (24개)
├── payment/       # Toss PG 연동, 결제 승인/매입/취소    (38개)
├── settlement/    # 정산 자동화, Elasticsearch 검색       (52개) ⭐
├── category/      # 다계층 이커머스 카테고리
├── coupon/        # 쿠폰 발급/사용/만료                  (16개)
├── review/        # 상품 리뷰                            (11개)
├── game/          # 오목/바둑 (Controller만, 미완성)     🚧
└── common/        # JWT, Security, Batch, Web 설정
```

각 도메인은 헥사고날 구조로 분리:

```
{domain}/
├── adapter/
│   ├── in/web/        # REST Controller, Request/Response
│   └── out/
│       ├── persistence/   # JPA Entity, Repository, Mapper
│       └── search/        # Elasticsearch Adapter
├── application/
│   ├── port/
│   │   ├── in/        # UseCase 인터페이스
│   │   └── out/       # Port 인터페이스
│   └── service/       # UseCase 구현체
└── domain/            # 순수 도메인 객체 (POJO)
```

### 2.2 주요 의존성

| 분야 | 라이브러리 |
|------|-----------|
| 핵심 | Spring Boot 3.5.10, Java 21 |
| DB | PostgreSQL 17, Flyway 11.7.2, Hibernate JPA |
| 검색 | Elasticsearch 8.x (Nori Analyzer) |
| 인증 | JJWT (JWT), Spring Security |
| 결제 | Toss Payments PG 연동 |
| 배치 | Spring Batch (CronJob) |
| 이메일 | Spring Mail (Gmail SMTP) |
| 코드생성 | MapStruct 1.6.3, Lombok |
| API 문서 | SpringDoc OpenAPI 2.8.0 (Swagger) |
| 모니터링 | Micrometer Prometheus, JaCoCo |
| 품질 | SonarCloud, Snyk |

### 2.3 구현된 기능

| 도메인 | 기능 | 상태 |
|--------|------|------|
| User | 회원가입, 로그인, JWT, 비밀번호 재설정 | ✅ |
| Product | CRUD, 상태 관리, 이미지 업로드, 검색 | ✅ |
| Order | 주문 생성, 상태 변경, 취소/환불 | ✅ |
| Payment | 승인/매입/취소, PG 연동, 부분 환불 | ✅ |
| Settlement | 자동 생성/확정, 환불 조정, 검색 | ✅ |
| Category | 다계층 카테고리 (슬러그 기반) CRUD | ✅ |
| Coupon | 생성/조회/사용, 만료, 제한 설정 | ✅ |
| Review | 상품 리뷰 CRUD, 평점 | ✅ |
| Game | Baduk / Gomoku UI | 🚧 미완성 |
| Refund | 부분/전액 환불, 멱등성, 동시성 제어 | ✅ |

### 2.4 정산 플로우 (핵심 도메인)

```
결제 완료 (CAPTURED)
    └─→ Settlement 생성 (REQUESTED)
            └─→ 배치 새벽 02:00 → PROCESSING
                    └─→ 배치 새벽 03:00 → DONE / CONFIRMED
                            ├─→ 환불 발생 시 → SettlementAdjustment 생성
                            └─→ Elasticsearch 인덱싱
```

### 2.5 트랜잭션 & 동시성

- **환불**: Pessimistic Lock (비관적 락) + 멱등성 키 (Idempotency-Key 헤더)
- **배치**: Spring Batch JobRepository 기반 중복 실행 방지
- **낙관적 락**: 미구현

---

## 3. 프론트엔드

### 3.1 라우트 구조

| 권한 | 경로 | 페이지 |
|------|------|--------|
| 공개 | `/login` | 일반 사용자 로그인 |
| 공개 | `/admin/login` | 관리자/매니저 로그인·회원가입 |
| 공개 | `/register` | 일반 회원가입 |
| 공개 | `/forgot-password`, `/reset-password` | 비밀번호 재설정 |
| USER | `/order`, `/cart`, `/mypage` | 주문·장바구니·마이페이지 |
| USER | `/games`, `/games/gomoku`, `/games/baduk` | 게임 |
| USER | `/viewer` | 뷰어 |
| ADMIN·MANAGER | `/admin` | 관리자 대시보드 |
| ADMIN·MANAGER | `/admin/settlement` | 정산 관리 |
| ADMIN·MANAGER | `/settlement/search` | 정산 조회 |
| ADMIN·MANAGER | `/product`, `/categories`, `/tags` | 상품·카테고리·태그 |
| ADMIN만 | `/admin/system/ecommerce-categories` | 이커머스 카테고리 |

### 3.2 주요 라이브러리

| 분야 | 라이브러리 | 버전 |
|------|-----------|------|
| Runtime | React, React DOM | 18.2.0 |
| 라우팅 | react-router-dom | 6.20.0 |
| HTTP | Axios | 1.6.2 |
| 결제 | @tosspayments/payment-widget-sdk | 0.12.1 |
| UI | Tailwind CSS | 3.4.0 |
| 언어 | TypeScript | 5.2.2 |
| 빌드 | Vite | 5.0.8 |
| 테스트 | Vitest, @testing-library/react | 4.0.18, 16.3.2 |
| Mock | MSW (Mock Service Worker) | 2.12.10 |

### 3.3 API 모듈 구조

```
frontend/src/api/
├── axios.ts        # 인스턴스 + Interceptor (JWT 자동 주입, 에러 처리)
├── auth.ts         # 로그인, 회원가입, 토큰 저장
├── product.ts      # 상품 CRUD
├── order.ts        # 주문 API
├── payment.ts      # 결제 API
├── settlement.ts   # 정산 검색·상세·승인·반려
├── category.ts     # 카테고리 API
├── coupon.ts       # 쿠폰 API
├── review.ts       # 리뷰 API
├── refund.ts       # 환불 API
├── tag.ts          # 태그 API
└── admin.ts        # 관리자 API
```

**Interceptor 처리**:
- 401 → 세션 만료, localStorage 삭제 후 `/login` 리다이렉트
- 403 → 권한 오류 토스트
- 500 → 서버 오류 토스트
- Network Error → 네트워크 오류 토스트

### 3.4 상태 관리

- `ToastContext` — 알림 메시지 (success / error / warning / info)
- `CartContext` — 장바구니 상태

---

## 4. 데이터베이스 마이그레이션 (Flyway)

| 버전 | 파일 | 내용 |
|------|------|------|
| V1 | init.sql | users 테이블 |
| V2 | create_order_payment_settlement.sql | orders, payments, settlements |
| V3 | add_indexes_and_constraints.sql | 인덱스, 외래키 |
| V4 | refunds_and_settlement_adjustments.sql | refunds, settlement_adjustments |
| V5 | settlement_index_queue.sql | ES 동기화 큐 |
| V6 | settlement_schedule_config.sql | 배치 스케줄 설정 |
| V7 | add_settlement_approval_fields.sql | 정산 승인 필드 |
| V8 | add_user_status_column.sql | users.status |
| V9 | alter_settlements_split_amount.sql | payment_amount / commission / net_amount 분리 |
| V10 | create_products_table.sql | products |
| V11 | create_password_reset_tokens_table.sql | 비밀번호 재설정 토큰 |
| V12 | create_categories_and_tags_tables.sql | categories, tags, 연결 테이블 |
| V13 | create_ecommerce_categories_table.sql | 다계층 이커머스 카테고리 |
| V14 | create_product_images_table.sql | product_images |
| V15 | add_product_id_to_orders.sql | orders.product_id |
| V16 | fix_pg_transaction_id_length.sql | pg_transaction_id 길이 수정 |
| V17 | seed_data.sql | 테스트 시드 (사용자 10, 상품 20, 주문/결제/정산 1000건) |
| V18 | add_seed_manager.sql | MANAGER 시드 계정 |
| V19 | create_reviews_table.sql | reviews (rating 1–5) |
| V20 | create_coupons_table.sql | coupons, coupon_usages |
| V21 | seed_january_2026_data.sql | 2026년 1월 완료 정산 310건 |

---

## 5. CI/CD (.github/workflows/ci.yml)

```
push / PR 트리거
    └─→ changes (변경 파일 감지: backend / frontend 분류)
            ├─→ backend-ci   (PostgreSQL + ES 서비스 포함)
            │       ├─ Gradle 빌드 & 테스트
            │       ├─ JaCoCo 커버리지 PR 댓글
            │       ├─ SonarCloud 품질 분석
            │       └─ Snyk 보안 스캔
            │
            ├─→ backend-ghcr  (push 한정)
            │       └─ Docker 빌드 → GHCR Push
            │
            ├─→ frontend-ci
            │       ├─ TypeScript 타입 체크
            │       ├─ ESLint
            │       ├─ Vite 빌드
            │       └─ Snyk 보안 스캔
            │
            ├─→ frontend-tests  ⚠️ 현재 비활성화 (if: false)
            │       └─ Vitest 실행
            │
            └─→ frontend-ghcr  (push 한정)
                    └─ Docker 빌드 → GHCR Push
```

---

## 6. 인프라

| 컴포넌트 | 내용 |
|---------|------|
| DB | PostgreSQL 17, 스키마: opslab |
| 검색 | Elasticsearch 8.x (Nori 한글 분석기) |
| 배치 | Spring Batch (K8s CronJob, 02:00 / 03:00) |
| 모니터링 | Prometheus (9090) + Grafana (3000, admin/admin) |
| 이미지 레지스트리 | GHCR (ghcr.io/myoungsoo7/settlement) |
| 오케스트레이션 | Kubernetes (k8s/ 디렉토리) |

---

## 7. 주요 문제점 & 개선 포인트

### 즉시 개선 필요

| 항목 | 내용 |
|------|------|
| 🔴 Game 도메인 미완성 | Controller만 존재, Service / Domain 없음 |
| 🔴 프론트 테스트 비활성화 | CI에서 `if: false`로 완전 스킵 중 |
| 🟡 Pessimistic Lock 성능 | 환불 동시성 처리 시 lock contention 위험 |
| 🟡 N+1 쿼리 | @EntityGraph / Fetch Join 미적용 |

### 단기 개선

| 항목 | 내용 |
|------|------|
| Redis 캐싱 미구현 | 카테고리·태그·상품 등 반복 조회 데이터 캐싱 필요 |
| Refresh Token 없음 | JWT 만료 시 재로그인만 가능 |
| 예외 처리 비일관성 | 도메인별 핸들러 파편화, GlobalExceptionHandler 통일 필요 |
| 감시(Audit) 로깅 없음 | 엔티티 변경 추적 불가 |

### 중기 개선

| 항목 | 내용 |
|------|------|
| WebSocket 미지원 | 실시간 게임 기능 구현 불가 |
| API 타입 안전성 | 백엔드 DTO ↔ 프론트 타입 수동 관리 → OpenAPI Generator 검토 |
| 권한 모델 단순 | ADMIN/MANAGER/USER 3단계 → RBAC 세분화 |
| 로그 수집 없음 | stdout만 사용 → ELK / Loki 도입 검토 |

---

## 8. 종합 평가

| 항목 | 평가 |
|------|------|
| 아키텍처 | 🟢 우수 — 헥사고날 구조 명확 |
| 도메인 모델 | 🟢 우수 — 비즈니스 로직 분리 |
| DB 관리 | 🟢 좋음 — Flyway V21까지 버전 관리 |
| API 설계 | 🟢 좋음 — RESTful 원칙 준수 |
| CI/CD | 🟢 좋음 — 변경 감지 병렬 빌드, 코드 품질 관리 |
| 보안 | 🟡 보통 — JWT 기본, Refresh Token 없음 |
| 테스트 | 🔴 부족 — 프론트 테스트 미실행 |
| 모니터링 | 🟡 보통 — Prometheus/Grafana 있으나 Alert 미설정 |

### 권장 개선 순서

1. **Phase 1** — Game 도메인 완성 + 프론트 테스트 활성화
2. **Phase 2** — Redis 캐싱 + Refresh Token + 권한 모델 개선
3. **Phase 3** — WebSocket 실시간 기능 + 모니터링 고도화 (ELK / Alert)