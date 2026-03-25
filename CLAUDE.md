# CLAUDE.md

이 파일은 Claude Code가 이 프로젝트에서 작업할 때 참고하는 가이드입니다.

## 프로젝트 개요

**Lemuel** — 전자상거래 주문·결제·정산 통합 시스템 (모노레포: 백엔드 + 프론트엔드)

## 빌드 및 실행 명령

### 백엔드 (Spring Boot, Java 21)

```bash
# 빌드 (테스트 포함)
./gradlew build

# 빌드 (테스트 제외)
./gradlew build -x test

# 테스트 실행
./gradlew test

# 실행
./gradlew bootRun

# JaCoCo 커버리지 리포트
./gradlew jacocoTestReport
```

### 프론트엔드 (React 18, Vite)

```bash
cd frontend
npm install
npm run dev          # 개발 서버 (포트 5173)
npm run build        # 프로덕션 빌드
npm run test:run     # Vitest 실행
npm run typecheck    # TypeScript 타입 체크
npm run lint         # ESLint
```

### Docker Compose

```bash
docker compose up -d          # 전체 서비스 실행
docker compose down            # 중지
docker compose logs -f app     # 백엔드 로그
```

## 아키텍처

### 헥사고날 (Ports & Adapters) 구조

모든 도메인 패키지는 다음 구조를 따릅니다:

```
{domain}/
├── adapter/
│   ├── in/web/              # REST Controller, Request/Response DTO
│   └── out/
│       ├── persistence/     # JPA Entity, Repository, Mapper
│       └── search/          # Elasticsearch Adapter (정산 도메인)
├── application/
│   ├── port/
│   │   ├── in/              # UseCase 인터페이스
│   │   └── out/             # Port 인터페이스
│   └── service/             # UseCase 구현체
└── domain/                  # 순수 도메인 객체 (POJO, 프레임워크 의존성 없음)
```

**의존성 규칙**: domain ← application ← adapter. domain 레이어는 외부 의존성이 없어야 합니다.

### 도메인 패키지 (github.lms.lemuel)

| 패키지 | 역할 |
|---------|------|
| user | 인증/인가, JWT, 비밀번호 재설정 |
| product | 상품 CRUD, 이미지, Elasticsearch 검색 |
| order | 주문 생성/상태 변경/취소/환불 |
| payment | Toss PG 연동, 결제 승인/매입/취소, 환불 |
| settlement | 정산 자동화, 배치, Elasticsearch 검색 |
| category | 다계층 이커머스 카테고리 |
| coupon | 쿠폰 발급/사용/만료 |
| review | 상품 리뷰 |
| game | 오목/바둑 (미완성 - Controller만 존재) |
| common | JWT, Security, Batch, Cache, Web 설정, 예외 처리 |

### 프론트엔드 구조

```
frontend/src/
├── api/           # API 서비스 (axios 인스턴스 + JWT 인터셉터)
├── components/    # 재사용 가능한 UI 컴포넌트
├── contexts/      # React Context (Toast, Cart)
├── pages/         # 페이지 컴포넌트
├── types/         # TypeScript 타입 정의
└── __tests__/     # 테스트
```

## 주요 기술적 결정사항

### 동시성 제어

- **환불**: `PESSIMISTIC_WRITE` 락 + `Idempotency-Key` 헤더 기반 멱등성 보장
- **배치**: Spring Batch JobRepository 기반 중복 실행 방지

### 정산 배치 스케줄

- 매일 02:00 — 전날 CAPTURED 결제 → PENDING 정산 생성
- 매일 03:00 — PENDING 정산 → CONFIRMED 확정
- 매일 03:10 — PENDING 정산 조정 → CONFIRMED 확정

### 데이터베이스

- PostgreSQL 17, 스키마: `opslab`
- Flyway 마이그레이션: V1 ~ V21 (src/main/resources/db/migration/)
- Elasticsearch 8.x: Nori 한글 분석기, 정산 검색 인덱스

### 코드 생성

- **MapStruct**: 도메인 ↔ JPA 엔티티 매핑 (`@Mapper` 인터페이스)
- **Lombok**: 보일러플레이트 제거 (`@Getter`, `@Builder`, `@NoArgsConstructor` 등)

## 코딩 컨벤션

- 새로운 도메인 기능 추가 시 헥사고날 구조를 따를 것
- domain 레이어에 Spring/JPA 의존성을 넣지 않을 것
- UseCase 인터페이스를 통해 adapter → application 호출
- Port 인터페이스를 통해 application → adapter(out) 호출
- JPA 엔티티와 도메인 객체를 분리하고 MapStruct Mapper로 변환
- REST 컨트롤러의 Request/Response DTO는 `adapter/in/web/dto/` 에 위치

## 테스트

- 백엔드 테스트: `src/test/java/` (JUnit 5, H2 인메모리 DB)
- 프론트엔드 테스트: `frontend/src/__tests__/` (Vitest, Testing Library, MSW)
- 통합 테스트: `ShoppingFlowIntegrationTest` (주문→결제→정산 E2E)
- 커버리지 최소 70% (JaCoCo, `jacocoTestCoverageVerification`)

## 환경 변수

`.env.example` 참조. 주요 변수:

- `POSTGRES_USER`, `POSTGRES_PASSWORD` — DB 접속
- `ELASTICSEARCH_USER`, `ELASTICSEARCH_PASSWORD` — ES 접속
- `JWT_ISSUER`, `JWT_SECRET` — JWT 설정
- `MAIL_USERNAME`, `MAIL_PASSWORD` — 이메일 발송
- `SNYK_TOKEN`, `SONAR_TOKEN` — 보안/품질 스캔

## CI/CD

GitHub Actions (`ci.yml`):
- Backend: Gradle 빌드 → JaCoCo → SonarCloud → Snyk → GHCR push
- Frontend: TypeScript 체크 → ESLint → Vite 빌드 → Snyk → GHCR push
- 변경 파일 감지로 backend/frontend 선택적 실행

## 라이선스

AGPL-3.0 (iText 8 의존성으로 인해)
