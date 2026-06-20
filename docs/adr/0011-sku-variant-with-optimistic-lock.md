# ADR 0011 — ProductVariant (SKU) + Optimistic Lock

- 상태: Accepted
- 일자: 2026-04-28

## 컨텍스트

초기 `Product` 도메인은 단일 `stock_quantity` 만 보유 — 색상·사이즈 옵션 상품 모델링 불가.
또한 동시 주문 시 race condition 으로 음수 재고 가능.

면접 질문 "100명이 동시에 같은 상품 주문하면 어떻게 처리하나요?" 에 대응 필요.

## 결정

별도 `product_variants` 테이블 + JPA `@Version` 기반 Optimistic Lock + 애플리케이션
계층 재시도.

### 도메인 모델

```java
public class ProductVariant {
    private Long id, productId;
    private String sku;              // 외부 노출용 변경 불가 식별자
    private String optionName;       // "색상:빨강/사이즈:L"
    private BigDecimal additionalPrice;  // 옵션별 가산금
    private int stockQuantity;
    private long version;            // ★ Optimistic Lock
}
```

### 재시도 전략

`DecreaseVariantStockService.decrease()`:
- 최대 5회 재시도
- 지수 백오프: 10ms → 20ms → 40ms → 80ms → 160ms
- 매 시도마다 `REQUIRES_NEW` 트랜잭션 (1차 캐시 stale 회피)
- 한계 초과 시 `StockConcurrencyException` + `variant.stock.decrease.failure` 메트릭

### 메트릭 가시화

- `variant.stock.decrease.success` — 성공
- `variant.stock.decrease.retry` — 충돌 발생 (재시도)
- `variant.stock.decrease.failure` — 한계 초과

retry/success 비율 > 1.0 이면 hot SKU → Redis 분산 락 격상 검토 신호.

## 결과

- `VariantStockConcurrencyIT` 통합 테스트로 100 스레드 / 재고 50 / 정확히 50/50 분포 검증
- 음수 재고 0건 보장 (도메인 불변식이 race 환경에서도 깨지지 않음)

## 대안

- **Pessimistic Lock** (`SELECT ... FOR UPDATE`): 단순하지만 낮은 동시성 환경에서도 락 대기로
  처리량 제한. 매우 hot 한 SKU 가 아니면 Optimistic 이 더 효율적
- **Redis 분산 락**: 별도 인프라 필요. 현재 RPS 대에서는 과한 비용
- **Saga + Compensation**: 결제 실패 후 재고 복구가 너무 복잡. 단일 트랜잭션 안에서
  차감하는 현재 방식이 단순하고 안전

## 참조

- [ProductVariant.java](../../order-service/src/main/java/github/lms/lemuel/product/domain/ProductVariant.java)
- [DecreaseVariantStockService.java](../../order-service/src/main/java/github/lms/lemuel/product/application/service/DecreaseVariantStockService.java)
- [VariantStockConcurrencyIT.java](../../order-service/src/test/java/github/lms/lemuel/product/application/service/VariantStockConcurrencyIT.java)
