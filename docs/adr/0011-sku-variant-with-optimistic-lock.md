# ADR 0011 — ProductVariant (SKU) + 원자적 조건부 UPDATE 재고 차감

- 상태: Accepted (2026-04-28) → Updated (2026-06-15)
- 일자: 2026-04-28 (최초), 2026-06-15 (동시성 전략 갱신)

> **갱신 이력 (2026-06-15)**: 최초 결정은 JPA `@Version` Optimistic Lock + 애플리케이션 계층
> 5회 백오프 재시도였으나, 핫 SKU 에서 재시도 폭증·한계 초과 실패가 발생하는 구조적 한계가
> 있어 **원자적 조건부 UPDATE** 방식으로 전환했다. 본문은 현재 구현 기준으로 갱신했고,
> 폐기된 낙관 락 재시도 전략은 아래 "대안"에 기록으로 남긴다.

## 컨텍스트

초기 `Product` 도메인은 단일 `stock_quantity` 만 보유 — 색상·사이즈 옵션 상품 모델링 불가.
또한 동시 주문 시 race condition 으로 음수 재고(초과판매) 가능.

면접 질문 "100명이 동시에 같은 상품 주문하면 어떻게 처리하나요?" 에 대응 필요.

## 결정

별도 `product_variants` 테이블 + **단일 원자적 조건부 UPDATE** 로 재고 차감.
"재고 검증 + 차감 + 매진 전이" 를 DB row 락 안에서 한 번의 쿼리로 처리한다.

```sql
UPDATE product_variants
   SET stock = stock - :q
 WHERE id = :id
   AND stock >= :q
   AND status <> 'DISCONTINUED'
```

- 영향 행 1 → 차감 성공. 차감된 최종 상태를 다시 읽어 반환.
- 영향 행 0 → 재고 부족·단종·미존재 중 하나. 원인을 분류해 적절한 예외로 전환.

낙관 락(`@Version`)+재시도와 달리 충돌 여부와 무관하게 단일 쿼리이므로 락 대기·충돌 재시도·
재시도 한계 실패가 발생하지 않으며, 초과판매도 원천 차단된다. 별도 백오프 재시도 루프가 필요 없다.

### 도메인 모델

```java
public class ProductVariant {
    private Long id, productId;
    private String sku;              // 외부 노출용 변경 불가 식별자
    private String optionName;       // "색상:빨강/사이즈:L"
    private BigDecimal additionalPrice;  // 옵션별 가산금
    private int stockQuantity;
    private long version;            // @Version — lost update 방지 안전망 (V36). 핫 차감 경로는 조건부 UPDATE 사용
}
```

### 차감 전략

`DecreaseVariantStockService.decrease()`:
- `savePort.decreaseStockIfAvailable(variantId, quantity)` 단일 조건부 UPDATE 실행
- 영향 행 0 → `classifyFailure()` 로 원인 분류
  - 재고 부족 → `InsufficientStockException`
  - 단종(`DISCONTINUED`) → `IllegalStateException`
  - 미존재 → `IllegalArgumentException`
- 결제 트랜잭션 안에서 독립 커밋이 필요하면 `decreaseInNewTransaction()` (`REQUIRES_NEW`)

### 메트릭 가시화

- `variant.stock.decrease.success` — 차감 성공 누적
- `variant.stock.decrease.rejected` — 재고 부족·단종 등으로 차감 거절된 누적

## 결과

- `VariantStockConcurrencyIT` 통합 테스트로 100 스레드 / 재고 50 → 정확히 50 성공 / 50 `InsufficientStock` /
  최종 재고 0 / 음수 재고 0건 검증
- 음수 재고 0건 보장 (도메인 불변식이 race 환경에서도 깨지지 않음)
- 충돌 재시도가 없으므로 hot SKU 에서도 처리량이 안정적

## 대안

- **Optimistic Lock (`@Version`) + 백오프 재시도** *(최초 채택 → 폐기)*: 충돌 시에만 재시도 비용이
  발생해 저경합 환경엔 좋지만, 핫 SKU 에서는 다수 스레드가 같은 행을 노려 재시도가 폭증하고
  5회 한계 초과 실패(`StockConcurrencyException`)가 빈발했다. 조건부 UPDATE 가 같은 정확성을
  재시도 없이 달성하므로 대체함. (`StockConcurrencyException` 타입은 코드에 남아 있으나 차감 경로에서 미사용)
- **Pessimistic Lock** (`SELECT ... FOR UPDATE`): 단순하지만 충돌이 없는 SKU 에서도 항상 락 대기 →
  처리량 제한. 조건부 UPDATE 가 동일 안전성을 더 낮은 비용으로 제공
- **Redis 분산 락**: 별도 인프라 필요. 현재 RPS 대에서는 과한 비용
- **Saga + Compensation**: 결제 실패 후 재고 복구가 너무 복잡. 단일 트랜잭션 안에서 차감하는
  현재 방식이 단순하고 안전

## 참조

- [ProductVariant.java](../../order-service/src/main/java/github/lms/lemuel/product/domain/ProductVariant.java)
- [DecreaseVariantStockService.java](../../order-service/src/main/java/github/lms/lemuel/product/application/service/DecreaseVariantStockService.java)
- [VariantStockConcurrencyIT.java](../../order-service/src/test/java/github/lms/lemuel/product/application/service/VariantStockConcurrencyIT.java)
