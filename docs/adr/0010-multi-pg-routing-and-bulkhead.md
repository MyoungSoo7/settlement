# ADR 0010 — 다중 PG 추상화 + Bulkhead 격벽

- 상태: Accepted
- 일자: 2026-04-28

## 컨텍스트

초기 프로젝트는 Toss Payments 단일 PG 만 연동되어있었다. 이커머스/결제 회사 면접에서
"PG 가 30분 다운되면?" 질문에 대응 불가. 또한 카드/계좌이체/간편결제마다 PG 별 수수료·
지원 결제수단이 다르므로 단일 PG 운영은 비현실적.

## 결정

`PaymentGatewayAdapter` 인터페이스 + 4 개 PG 어댑터 (TOSS / KCP / NICE / INICIS) +
`PgRouter` 라우팅 전략. 거래 ID prefix (`TOSS:xxx` / `KCP:xxx`) 로 매입·환불 시 동일 PG
자동 라우팅.

### 라우팅 정책

```yaml
app:
  pg:
    routing:
      primary-by-method: { CARD: TOSS, KAKAO_PAY: NICE, BANK_TRANSFER: KCP }
      fallback-chain: [TOSS, NICE, KCP, INICIS]
      high-amount-threshold: 1000000
      high-amount-preferred: NICE
```

1. 고액 거래 (1백만원 이상) → high-amount preferred PG 우선
2. 결제 수단별 1순위 → primary-by-method
3. 1순위 PG 가 unhealthy 면 fallback chain 순회

### 격벽 (Bulkhead)

PG 별 독립 Resilience4j CircuitBreaker:
- `tossPg`, `kcpPg`, `nicePg`, `inicisPg`
- 50% 실패율 / 30초 OPEN
- 한 PG 의 장애가 다른 PG 로 전이되지 않음

## 결과

- 단일 인터페이스 (`PgClientPort`) 는 그대로 유지 → 도메인 코드 변경 0
- 운영 시점에 외부 설정으로 정책 변경 가능
- `pg.routing.requests{provider, reason, method}` 메트릭으로 라우팅 결정 가시화

## 대안

- **DB 컬럼 추가** (`payments.pg_provider`) — 운영 데이터 마이그레이션 필요. 거래 ID prefix
  방식이 더 가벼움
- **Strategy 빈 + @Qualifier** — Spring 의존도 강해짐. PgRouter 클래스가 더 명시적

## 참조

- [PgRouter.java](../../order-service/src/main/java/github/lms/lemuel/payment/adapter/out/pg/PgRouter.java)
- [PgRouterTest.java](../../order-service/src/test/java/github/lms/lemuel/payment/adapter/out/pg/PgRouterTest.java)
