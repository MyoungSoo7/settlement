# 기술 스택 & 기능

## 🛠️ 기술 스택

### Backend
- **Java**: 25
- **Spring Boot**: 4.0.4
- **Spring Security**: JWT 기반 인증
- **Spring Data JPA**: ORM 레이어
- **Spring Batch**: 정산 배치 작업
- **Flyway**: 데이터베이스 마이그레이션

### Database
- **PostgreSQL**: 17 (메인 데이터베이스)
- **Elasticsearch**: 8.x (검색 엔진, Cloud)
  - **analysis-nori**: 한글 형태소 분석 플러그인

### Monitoring & Observability
- **Prometheus**: 메트릭 수집
- **Grafana**: 시각화 대시보드
- **Spring Boot Actuator**: 애플리케이션 모니터링

### Build & Tools
- **Gradle**: 빌드 도구 (Kotlin DSL)
- **Docker & Docker Compose**: 컨테이너화
- **Swagger/OpenAPI**: API 문서화

### Test
- **JUnit 5**: 단위/통합 테스트 프레임워크
- **Spring Boot Test**: 통합 테스트 지원

## 🎯 핵심 기능

### 1. 인증 및 사용자 관리
- **JWT 기반 인증**: 토큰 기반 무상태(stateless) 인증
- **Spring Security 통합**: 보안 필터 체인
- **사용자 권한 관리**: 역할 기반 접근 제어

### 2. 주문 관리 (Order)
- **주문 생성**: 고객 주문 데이터 생성
- **주문 상태 관리**:
  - `CREATED`: 주문 생성 (결제 대기)
  - `PAID`: 결제 완료
  - `CANCELED`: 결제 전 취소
  - `REFUNDED`: 결제 후 환불 완료
- **주문 이력 추적**: 전체 주문 생애주기 관리

### 3. 결제 처리 (Payment)
- **다양한 결제 수단 지원**: 카드, 간편결제 등
- **결제 상태 관리**:
  - `READY`: 결제 준비
  - `AUTHORIZED`: 승인 완료
  - `CAPTURED`: 매입 완료 (실제 결제)
  - `FAILED`: 결제 실패
  - `CANCELED`: 승인 취소
  - `REFUNDED`: 전액 환불
- **PG 연동**: 외부 결제 게이트웨이 트랜잭션 관리
- **결제 금액 추적**: `refundedAmount` 필드로 환불 누적 관리

### 4. 환불 처리 (Refund) - v0.2.0
- **부분/전액 환불**: 유연한 환불 정책
- **멱등성 보장**: `Idempotency-Key` 헤더 기반 중복 환불 방지
- **동시성 제어**: PESSIMISTIC_WRITE 락으로 환불 금액 초과 방지
- **환불 상태 관리**:
  - `REQUESTED`: 환불 요청
  - `APPROVED`: 환불 승인
  - `COMPLETED`: 환불 완료
  - `FAILED`: 환불 실패
  - `CANCELED`: 환불 취소
- **환불 이력 분리**: Refund 엔티티로 독립 관리 (회계 감사 추적)

### 5. 정산 시스템 (Settlement)
- **자동 정산 배치**:
  - 매일 새벽 2시: 전날 CAPTURED 결제 → PENDING 정산 생성
  - 매일 새벽 3시: PENDING → CONFIRMED 확정
  - 매일 새벽 3시 10분: 정산 조정 확정
- **정산 상태 관리**:
  - `PENDING`: 정산 대기
  - `CONFIRMED`: 정산 확정
- **정산 조정 (SettlementAdjustment)**:
  - CONFIRMED 정산 후 환불 발생 시 자동 조정 레코드 생성
  - 회계 감사 추적 및 재무 정확성 보장
- **배치 멱등성**: 중복 정산 방지 로직

### 6. 통합 검색 (Elasticsearch)
- **실시간 검색**: 정산, 주문, 결제, 환불 데이터 통합 검색
- **한글 형태소 분석**: Nori Analyzer 적용
- **복합 필터링**:
  - 날짜 범위 검색
  - 금액 범위 검색
  - 상태별 필터링
  - 환불 여부 필터링
- **집계(Aggregation)**:
  - 총 금액, 평균, 최소/최대 금액
  - 상태별 카운트
  - 환불 카운트
  - 날짜별 집계
- **이벤트 기반 동기화**: PostgreSQL 변경 시 Elasticsearch 실시간 반영

### 7. 성능 모니터링
- **Prometheus 메트릭 수집**:
  - HTTP 요청 수/응답 시간
  - JVM 메모리/GC 메트릭
  - 데이터베이스 커넥션 풀
  - 커스텀 비즈니스 메트릭
- **Grafana 대시보드**:
  - 실시간 시각화
  - 알림 설정 (에러율, 응답시간 임계치)
  - Slack 연동 가능

### 8. 데이터베이스 마이그레이션
- **Flyway 통합**: 버전 기반 스키마 관리
- **자동 마이그레이션**: 애플리케이션 시작 시 자동 실행
- **롤백 지원**: 안전한 스키마 변경

### 9. API 문서화
- **Swagger UI**: 인터랙티브 API 문서
- **OpenAPI 3.0 규격**: 표준화된 API 스펙
- **자동 생성**: SpringDoc OpenAPI 기반

## 🔒 보안 기능

### 데이터베이스 제약 조건
- `payments.refunded_amount` CHECK (0 ~ amount)
- `refunds(payment_id, idempotency_key)` UNIQUE 인덱스
- `settlement_adjustments(refund_id)` UNIQUE 인덱스
- `refunds.amount` CHECK (> 0)
- `settlement_adjustments.amount` CHECK (< 0)

### 동시성 제어
- **비관적 락 (PESSIMISTIC_WRITE)**: 환불 처리 시 Payment row-level lock
- **멱등성 키**: 중복 요청 방지

### 트랜잭션 관리
- **Spring @Transactional**: 원자성 보장
- **격리 수준 제어**: 데이터 일관성 유지
