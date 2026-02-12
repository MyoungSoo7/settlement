# Lemuel - OpsLab 주문·결제·정산 시스템

## 📋 프로젝트 개요

**Lemuel**은 Spring Boot 기반의 엔터프라이즈급 주문·결제·정산 통합 시스템입니다.

- **Java**: 21
- **Spring Boot**: 3.5.10
- **PostgreSQL**: 17
- **버전**: v0.2.0

## 🎯 프로젝트 목적

전자상거래 및 결제 플랫폼에서 필수적인 주문, 결제, 정산 프로세스를 안전하고 효율적으로 관리하기 위해 개발되었습니다. 실무 표준 패턴을 적용하여 회계 감사 추적, 멱등성 보장, 동시성 제어 등의 핵심 기능을 구현했습니다.

## 💡 핵심 가치

### 사용자에게 제공하는 가치

1. **정확한 재무 관리**
   - 정산 오류 방지를 위한 엄격한 검증 및 제약 조건
   - 회계 감사 추적을 위한 모든 거래 이력 보관
   - 환불 처리 시 정산 자동 조정

2. **안전한 환불 처리**
   - 멱등성 보장으로 중복 환불 방지
   - 동시성 제어로 환불 금액 초과 방지
   - 부분/전액 환불 유연한 지원

3. **강력한 검색 및 모니터링**
   - Elasticsearch 기반 실시간 정산 검색
   - Prometheus & Grafana를 통한 성능 모니터링
   - 한글 형태소 분석으로 정확한 검색 결과

4. **자동화된 정산 배치**
   - 매일 자동 정산 생성 및 확정
   - 정산 조정 자동 처리
   - 배치 멱등성으로 안전한 재실행

## 🚀 주요 기능

### 1. 주문 관리
- 주문 생성 및 상태 관리 (CREATED, PAID, CANCELED, REFUNDED)
- 주문 이력 추적 및 조회

### 2. 결제 처리
- 다양한 결제 수단 지원 (카드, 간편결제)
- 결제 승인, 매입, 취소 처리
- PG 연동 트랜잭션 관리

### 3. 환불 시스템 (v0.2.0)
- **부분/전액 환불** 지원
- **멱등성 보장**: `Idempotency-Key` 헤더로 중복 환불 방지
- **동시성 제어**: 비관적 락으로 환불 금액 초과 방지
- **정산 조정**: 확정된 정산 후 환불 시 자동 조정 레코드 생성

### 4. 자동 정산
- **매일 새벽 2시**: 전날 결제 건 정산 생성
- **매일 새벽 3시**: 정산 확정
- **매일 새벽 3시 10분**: 정산 조정 확정
- 배치 멱등성으로 안전한 재실행 보장

### 5. 통합 검색 (Elasticsearch)
- 정산, 주문, 결제, 환불 데이터 통합 검색
- 한글 형태소 분석 (Nori Analyzer)
- 날짜/금액/상태별 복합 필터링
- 집계 기능 (총액, 평균, 상태별 카운트)

### 6. 성능 모니터링
- Prometheus 메트릭 수집
- Grafana 실시간 대시보드
- 에러율/응답시간 알림

## 🔥 v0.2.0 주요 업데이트 (2026-02-10)

### 환불 모델 개선
- ❌ **이전**: 부분환불 시 음수 Payment 레코드 생성 (비표준)
- ✅ **현재**: Refund 엔티티로 환불 이력 분리 관리 (실무 표준)

### 새로운 기능
1. **멱등성 보장**: 중복 환불 방지
2. **동시성 제어**: 환불 금액 초과 방지
3. **정산 조정**: 회계 감사 추적
4. **환불 누적 추적**: 실시간 환불 상태 관리

자세한 내용은 [ARCHITECTURE.md](docs/ARCHITECTURE.md)를 참고하세요.

## 📡 API 엔드포인트

### 환불 API

#### 환불 요청 (통합 API)
```http
POST /refunds/{paymentId}
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
Content-Type: application/json

{
  "amount": 5000.00,
  "reason": "고객 요청"
}
```

#### 전체 환불
```http
POST /refunds/full/{paymentId}
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440001
```

#### 부분 환불
```http
POST /refunds/partial/{paymentId}?refundAmount=5000.00
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440002
```

### 정산 검색 API

```http
GET /api/settlements/search?startDate=2026-01-01T00:00:00&endDate=2026-02-11T23:59:59&isRefunded=false&page=0&size=20
```

더 많은 API 정보는 Swagger UI (`http://localhost:8080/swagger-ui.html`)에서 확인하세요.

## 📚 문서

- **[SKILLS.md](docs/SKILLS.md)** - 기술 스택 및 기능 상세 설명
- **[ARCHITECTURE.md](docs/ARCHITECTURE.md)** - 시스템 아키텍처 및 설계
- **[ETC.md](ETC.md)** - 설치 가이드 및 트러블슈팅

## 🚀 빠른 시작

### 1. 사전 요구사항
- Java 21
- PostgreSQL 17
- Docker & Docker Compose
- Elastic Cloud 계정 (무료 트라이얼)

### 2. PostgreSQL 설정
```bash
# 데이터베이스 생성
createdb opslab

# 사용자 생성
psql -U postgres -c "CREATE USER inter WITH PASSWORD '1234';"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE opslab TO inter;"
```

### 3. 애플리케이션 실행
```bash
# Docker 인프라 실행 (Prometheus, Grafana)
docker-compose up -d

# 애플리케이션 실행
./gradlew bootRun
```

### 4. 접속 정보
- **API 서버**: http://localhost:8080
- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3000 (admin/admin)

자세한 설치 가이드는 [ETC.md](ETC.md)를 참고하세요.

## 🧪 테스트

```bash
./gradlew test
```

## 📄 라이선스

이 프로젝트는 내부 OpsLab 용도로 개발되었습니다.
