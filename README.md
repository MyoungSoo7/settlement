# Lemuel

전자상거래 주문 · 결제 · 정산 통합 시스템

[![CI](https://github.com/MyoungSoo7/settlement/actions/workflows/ci.yml/badge.svg)](https://github.com/MyoungSoo7/settlement/actions/workflows/ci.yml)

## 개요

Lemuel은 이커머스 환경에서 주문부터 결제, 정산까지의 전체 라이프사이클을 관리하는 풀스택 애플리케이션입니다. 헥사고날 아키텍처(Ports & Adapters)를 채택하여 도메인 로직의 독립성과 테스트 용이성을 확보했습니다.

| 항목 | 내용 |
|------|------|
| 백엔드 | Spring Boot 3.5.10, Java 21 |
| 프론트엔드 | React 18, TypeScript, Vite |
| 데이터베이스 | PostgreSQL 17, Elasticsearch 8.x |
| 아키텍처 | Hexagonal (Ports & Adapters) |
| 인프라 | Docker Compose, Kubernetes, GitHub Actions |

## 프로젝트 구조

```
lemuel/
├── src/main/java/           # 백엔드 (Spring Boot)
│   └── github/lms/lemuel/
│       ├── user/            # 인증/인가, JWT
│       ├── product/         # 상품 CRUD, 이미지, 검색
│       ├── order/           # 주문 생성/상태/취소/환불
│       ├── payment/         # Toss PG 연동, 결제 승인/매입/취소
│       ├── settlement/      # 정산 자동화, ES 검색
│       ├── category/        # 다계층 이커머스 카테고리
│       ├── coupon/          # 쿠폰 발급/사용/만료
│       ├── review/          # 상품 리뷰
│       └── common/          # JWT, Security, Batch, 공통 설정
├── src/main/resources/
│   └── db/migration/        # Flyway 마이그레이션 (V1~V21)
├── frontend/                # React 프론트엔드
├── k8s/                     # Kubernetes 매니페스트
├── monitoring/              # Prometheus / Grafana
├── docs/                    # 프로젝트 문서
└── build.gradle.kts
```

각 도메인은 헥사고날 구조를 따릅니다:

```
{domain}/
├── adapter/
│   ├── in/web/              # REST Controller, DTO
│   └── out/persistence/     # JPA Entity, Repository, Mapper
├── application/
│   ├── port/in/             # UseCase 인터페이스
│   ├── port/out/            # Port 인터페이스
│   └── service/             # UseCase 구현체
└── domain/                  # 순수 도메인 객체 (POJO)
```

## 주요 기능

### 주문 · 결제 · 정산

- **주문**: 생성, 상태 변경(CREATED → PAID → CANCELED/REFUNDED)
- **결제**: Toss Payments PG 연동, 승인/매입/취소
- **환불**: 부분/전액 환불, 멱등성 키(Idempotency-Key), 비관적 락 동시성 제어
- **정산**: 배치 자동 생성/확정, 환불 시 정산 조정(SettlementAdjustment), Elasticsearch 검색

```
결제 완료 (CAPTURED)
  → Settlement 생성 (PENDING)
    → 배치 새벽 02:00 → CONFIRMED
      → 환불 발생 시 → SettlementAdjustment 생성
      → Elasticsearch 인덱싱
```

### 상품 · 카테고리 · 쿠폰

- 상품 CRUD, 이미지 업로드, Elasticsearch 검색
- 다계층 이커머스 카테고리 (슬러그 기반)
- 쿠폰 발급/사용/만료, 사용 횟수 제한

### 인증 · 사용자

- JWT 기반 인증/인가 (ADMIN / MANAGER / USER)
- 비밀번호 재설정 (이메일 토큰)

### 프론트엔드

- 관리자 대시보드 (정산 관리, 상품/카테고리/태그 관리)
- 사용자 페이지 (주문, 장바구니, 마이페이지)
- 정산 검색 (복합 필터, 집계, 페이지네이션)
- Toss Payments 결제 위젯 연동

## 시작하기

### 사전 요구사항

- Java 21
- Node.js 20+
- Docker & Docker Compose
- PostgreSQL 17 (또는 Docker로 실행)

### 1. 환경 변수 설정

```bash
cp .env.example .env
# .env 파일에 DB, Elasticsearch, JWT, 메일 등 설정
```

### 2. Docker Compose로 실행

```bash
docker compose up -d
```

이 명령으로 다음 서비스가 시작됩니다:

| 서비스 | 포트 | 설명 |
|--------|------|------|
| PostgreSQL | 5433 | 데이터베이스 |
| Elasticsearch | 9200 | 검색 엔진 |
| Backend (app) | 8089 | Spring Boot API |
| Frontend | 3000 | Nginx + React SPA |
| Prometheus | 9090 | 메트릭 수집 |

### 3. 로컬 개발 (백엔드)

```bash
./gradlew bootRun
```

### 4. 로컬 개발 (프론트엔드)

```bash
cd frontend
npm install
npm run dev
```

`http://localhost:5173`에서 개발 서버에 접속합니다.

## 기술 스택

### 백엔드

| 분야 | 기술 |
|------|------|
| 프레임워크 | Spring Boot 3.5.10, Java 21 |
| 데이터베이스 | PostgreSQL 17, Flyway 11.7 |
| 검색 | Elasticsearch 8.x (Nori 한글 분석기) |
| 인증 | Spring Security, JJWT |
| 결제 | Toss Payments PG |
| 배치 | Spring Batch |
| 이메일 | Spring Mail (Gmail SMTP) |
| PDF | iText 8 (AGPL), Ghostscript |
| 캐시 | Caffeine |
| 코드 생성 | MapStruct, Lombok |
| API 문서 | SpringDoc OpenAPI (Swagger) |

### 프론트엔드

| 분야 | 기술 |
|------|------|
| UI | React 18, TypeScript |
| 빌드 | Vite 5 |
| 스타일 | Tailwind CSS 3.4 |
| HTTP | Axios |
| 결제 | @tosspayments/payment-widget-sdk |
| 테스트 | Vitest, Testing Library, MSW |

### 인프라 & 품질

| 분야 | 기술 |
|------|------|
| 컨테이너 | Docker, Docker Compose |
| 오케스트레이션 | Kubernetes (ArgoCD) |
| CI/CD | GitHub Actions |
| 모니터링 | Prometheus, Micrometer |
| 코드 품질 | SonarCloud, JaCoCo (커버리지 70%) |
| 보안 스캔 | Snyk |
| 이미지 레지스트리 | GHCR |

## API 문서

백엔드 서버 실행 후 Swagger UI에 접속:

```
http://localhost:8080/swagger-ui.html
```

## 테스트

### 백엔드

```bash
./gradlew test
```

- 단위 테스트: 도메인, 서비스, 컨트롤러
- 통합 테스트: 쇼핑 플로우 E2E
- 커버리지 최소 70% (JaCoCo)

### 프론트엔드

```bash
cd frontend
npm run test:run
```

## CI/CD

GitHub Actions로 자동화되어 있습니다:

- **Backend CI**: Gradle 빌드/테스트 → JaCoCo 커버리지 → SonarCloud → Snyk 보안 스캔
- **Frontend CI**: TypeScript 타입 체크 → ESLint → Vite 빌드 → Snyk
- **GHCR Push**: main 브랜치 push 시 Docker 이미지 빌드 후 GHCR에 push

## 문서

자세한 문서는 `docs/` 디렉토리를 참고하세요:

- [아키텍처](docs/ARCHITECTURE.md) - 시스템 아키텍처 및 도메인 설계
- [인프라](docs/INFRASTRUCTURE.md) - 인프라 구성
- [배포](docs/DEPLOYMENT.md) - 배포 가이드
- [모니터링](docs/MONITORING.md) - Prometheus / Grafana
- [보안](docs/SECURITY.md) - 보안 설계
- [설정](docs/SETUP.md) - 환경 설정 가이드
- [CI 설정](docs/CI_SETUP_GUIDE.md) - CI/CD 파이프라인

## 라이선스

이 프로젝트는 **AGPL-3.0** 라이선스 하에 배포됩니다 (iText 8 의존성).

- **iText 8**: PDF 생성 (AGPL 라이선스)
- **Ghostscript**: PDF 렌더링/압축 (외부 CLI 도구)
