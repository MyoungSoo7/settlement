# E2E 최종 검증 시나리오 — 가입부터 환불까지

사용자의 실제 구매 흐름을 한 번에 검증하는 End-to-End 시나리오.
Postman 컬렉션: [`postman-e2e-purchase-flow.json`](./postman-e2e-purchase-flow.json) (환경: [`postman-environment.json`](./postman-environment.json))

## 실행 방법

```bash
# 1. 전체 시스템 기동 (모든 서비스 healthy 확인)
docker compose up -d --build
docker compose ps    # 전부 Up (healthy) 대기

# 2-A. Postman: 컬렉션 + 환경 import 후 Runner 로 위→아래 실행
# 2-B. CLI (newman):
npx newman run docs/demo/postman-e2e-purchase-flow.json -e docs/demo/postman-environment.json
```

모든 요청은 **gateway(`http://localhost:8080`)** 를 경유한다 (라우팅 검증 겸용).
각 단계의 Postman test 스크립트가 기대 응답을 자동 assert 하므로, Runner 결과가 전부 green 이면 시나리오 통과.

## 테스트 데이터 초기화

- **Flyway 시드가 자동 적용**된다 (order-service 기동 시): `V17__seed_data.sql` 이 시드 사용자·상품·주문을 생성.
  `ON CONFLICT DO UPDATE` 라 재기동에도 안전.
- **관리자 토큰은 데모 자동 로그인**으로 얻는다: `POST /auth/dev/auto-login?role=ADMIN`
  (`lemuel.demo.enabled=true` — compose 기본값). ⚠️ V17 시드 계정의 BCrypt 해시는 주석(password123)과
  달라 비밀번호 로그인이 실제로는 불가하다 — E2E 검증 중 발견된 시드 데이터 불일치.
- 구매자 계정·상품·주문은 **시나리오가 매 실행마다 새로 생성**한다
  (이메일에 timestamp 포함 → 재실행 시 충돌 없음, 별도 클린업 불필요).
- 완전 초기화가 필요하면: `docker compose down -v && docker compose up -d --build` (볼륨 삭제 → 시드 재적용).

## 단계별 요청·기대 응답

| # | 단계 | 요청 | 주요 요청값 | 기대 응답 (assert) |
|---|------|------|------------|-------------------|
| 00 | 헬스체크 | `GET /actuator/health` | — | `200`, `status=UP` |
| 01 | 회원가입 | `POST /users` | `email`(유니크 자동생성), `password`, `name` | `2xx`, `id` 반환 → `E2E_USER_ID` 저장 |
| 02 | 로그인(구매자) | `POST /auth/login` | email, password | `200`, `token` 발급 → `E2E_TOKEN` |
| 03 | 로그인(관리자) | `POST /auth/dev/auto-login?role=ADMIN` (데모 기능) | — | `200`, `role=ADMIN` → `E2E_ADMIN_TOKEN` |
| 04 | 상품 생성 | `POST /api/products` | name, price=10000, stockQuantity=10 | `2xx`, `id` → `E2E_PRODUCT_ID` |
| 05 | 장바구니 담기 | `POST /users/{userId}/cart/items` | productId, quantity=1 | `2xx`, `items[]` 에 해당 상품 존재 |
| 06 | 주문 생성 | `POST /orders/multi` + 헤더 `Idempotency-Key` | userId, lines[{productId, quantity:1}] | `2xx`, `amount=10000` → `E2E_ORDER_ID`. 같은 키 재요청 시 중복 주문 없이 기존 주문 반환 |
| 07 | 결제 생성 | `POST /payments` | orderId, paymentMethod=CARD | `2xx`, `status=READY` → `E2E_PAYMENT_ID` |
| 08 | 결제 인증 | `PATCH /payments/{id}/authorize` | — | `200`, `status=AUTHORIZED` |
| 09 | 결제 캡처 | `PATCH /payments/{id}/capture` | — | `200`, `status=CAPTURED` — 이 시점에 Outbox→Kafka→settlement 정산 생성 |
| 10 | 취소 신청 | `POST /orders/{id}/cancellation-request` | reason | `200`, `status=CANCELLATION_REQUESTED` |
| 11 | **관리자 취소 승인** | `POST /orders/admin/{id}/cancellation-approve` (ADMIN 토큰) | reason | `200`, **`status=REFUNDED`** — 승인이 PG 전액 환불·재고 원복까지 자동 실행 |
| 12 | 환불 확인 ① | `GET /payments/{id}` | — | `200`, `status=REFUNDED` |
| 13 | 환불 확인 ② | `GET /api/payments/{id}/refunds` | — | `200`, `refunds[]` ≥ 1건, `totalRefundedAmount` = 주문 금액 |

## 검증 포인트 (도메인 규칙 대응)

- **Payment 상태머신**: `READY → AUTHORIZED → CAPTURED → REFUNDED` 전 구간을 한 시나리오로 통과 (8→9→11).
- **Order 상태머신**: `CREATED → PAID → CANCELLATION_REQUESTED → CANCELLATION_APPROVED → REFUNDED` —
  비정상 전이는 `OrderStatus.canTransitionTo()` 가 차단하므로, 순서를 바꿔 호출하면 4xx 로 실패한다(음성 검증에 활용 가능).
- **멱등성**: 6단계 `Idempotency-Key` 재전송 시 새 주문이 생기지 않아야 한다 (분산락 + DB UNIQUE).
- **취소 승인 = 환불 원자성**: 11단계 승인 한 번으로 주문 REFUNDED + 결제 REFUNDED + 환불 이력 기록이 모두 일어난다.
  PG 환불 실패 시 트랜잭션 롤백 → 주문은 CANCELLATION_REQUESTED 로 남는다.
- **정산 연쇄 (선택 확장)**: 9단계 캡처 → Outbox → Kafka → `settlement_db.settlements` 에 정산 생성
  (검증 실측: 10,000원 → 수수료 3.5% → net 9,650원, status REQUESTED). 11단계 환불 → `PaymentRefunded` 이벤트 →
  `settlement_payment_view.refunded_amount` 에 즉시 반영(실측 10,000원). `settlements.refunded_amount` 는
  정산 확정 배치가 뷰를 기준으로 반영하는 설계.
  ```bash
  docker compose exec settlement-db psql -U $POSTGRES_USER -d settlement_db \
    -c "SELECT payment_id, net_amount, status FROM settlements ORDER BY id DESC LIMIT 5;"
  ```

## 한계 / 참고

- PG(Toss) 는 로컬에서 시뮬레이션 어댑터로 동작한다 — 실 카드 승인 없이 상태 전이만 검증.
- Playwright smoke (`frontend/`) 는 UI 로그인 검증용이며, 이 컬렉션이 API 레벨 전체 흐름을 커버한다.
