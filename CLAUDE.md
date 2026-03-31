# Settlement 프로젝트

## 프로젝트 개요

주문, 결제, 정산, 승인을 처리하는 통합 시스템. 헥사고날 아키텍처 기반으로 도메인 로직과 인프라를 분리하여 유지보수성과 테스트 용이성을 확보한다.

## 기술 스택

| 구분 | 기술 |
|------|------|
| 언어 | Java 21 |
| 프레임워크 | Spring Boot 3.5.10 |
| 데이터베이스 | PostgreSQL 17 |
| 검색 엔진 | Elasticsearch 8.17 |
| PG 연동 | Toss Payments |
| 배치 처리 | Spring Batch |
| 캐시 | Caffeine Cache |
| PDF 생성 | iText PDF |
| 모니터링 | Micrometer + Prometheus |
| 마이그레이션 | Flyway (V1 ~ V22) |

## 패키지 구조 (헥사고날 아키텍처)

각 도메인은 아래 구조를 따른다:

```
src/main/java/
└── {domain}/
    ├── domain/          # 도메인 모델 (POJO)
    ├── application/
    │   ├── port/
    │   │   ├── in/      # 인바운드 포트 (유스케이스 인터페이스)
    │   │   └── out/     # 아웃바운드 포트 (영속성/외부 서비스 인터페이스)
    │   └── service/     # 유스케이스 구현체
    └── adapter/
        ├── in/
        │   └── web/     # REST 컨트롤러
        └── out/
            └── persistence/  # JPA 리포지토리, 엔티티
```

**도메인 목록:**

- `user` - 회원 관리, 인증/인가
- `order` - 주문 생성 및 상태 관리
- `payment` - 결제 승인, 캡처, 환불
- `settlement` - 정산 생성, 확정, PDF, ES 인덱싱
- `product` - 상품 CRUD, 이미지
- `category` - 카테고리 관리
- `coupon` - 쿠폰 발급 및 할인 적용
- `review` - 상품 리뷰
- `game` - 게임 관련 기능
- `common` - 공통 유틸리티, 예외 처리

## 도메인 규칙

### Payment 상태 흐름

```
READY → AUTHORIZED → CAPTURED → REFUNDED
```

### Settlement 상태 흐름

```
REQUESTED → PROCESSING → DONE
                       → FAILED
```

### Order 상태 흐름

```
CREATED → PAID → REFUNDED
              → CANCELED
```

### 수수료

- 정산 수수료율: **3%**

## 코딩 컨벤션

- **아키텍처**: 헥사고날 아키텍처 (Ports & Adapters)
- **포트/어댑터 패턴**: 인바운드 포트는 유스케이스 인터페이스, 아웃바운드 포트는 영속성/외부 서비스 인터페이스로 정의
- **도메인 모델**: 순수 POJO로 작성, 프레임워크 의존성 없음
- **DB 마이그레이션**: Flyway 사용, 버전 V1 ~ V22까지 관리
- **테스트**: 도메인 단위 테스트 우선, 서비스 테스트, 컨트롤러 테스트, 통합 테스트 순으로 작성

## 인프라

- **컨테이너**: Docker Compose (로컬 개발), Kubernetes deployment (운영)
- **리버스 프록시**: nginx
- **모니터링**: Prometheus + Micrometer
- **코드 커버리지**: JaCoCo 최소 **70%** 이상 유지

## 보안

| 항목 | 설정 |
|------|------|
| 인증 | JWT (HS256) |
| 비밀번호 해싱 | BCrypt (cost=12) |
| CORS | 환경변수로 허용 도메인 설정 |
| Rate Limiting | nginx 기반 요청 제한 |
| Actuator | 인증 필수, 비인가 접근 차단 |

## 빌드 및 실행 커맨드

```bash
# 빌드
./gradlew build

# 테스트 실행
./gradlew test

# Docker Compose 로컬 실행
docker compose up

# Docker Compose 종료
docker compose down

# 특정 프로파일로 실행
./gradlew bootRun --args='--spring.profiles.active=local'
```
