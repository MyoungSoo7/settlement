package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.DecreaseVariantStockUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.application.port.out.SaveProductVariantPort;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.exception.StockConcurrencyException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 옵션(SKU) 재고 차감 — Optimistic Lock 기반 동시성 제어.
 *
 * <p>흐름:
 * <ol>
 *   <li>variant 로드 → {@link ProductVariant#decreaseStock(int)} 호출 → save</li>
 *   <li>save 시 Hibernate 가 {@code UPDATE ... WHERE id = ? AND version = ?} 발행</li>
 *   <li>다른 트랜잭션이 먼저 커밋되어 version 이 바뀌었으면 0 row updated → 예외</li>
 *   <li>예외를 잡아 N 회 재시도 (지수 백오프)</li>
 *   <li>재시도 한계 초과 시 {@link StockConcurrencyException} 으로 전환해 운영팀이 인지</li>
 * </ol>
 *
 * <p>각 재시도마다 변경 사항은 {@code REQUIRES_NEW} 트랜잭션으로 격리되어 이전 시도의
 * stale 1차 캐시를 끌고 가지 않는다.
 *
 * <p>운영 메트릭:
 * <ul>
 *   <li>{@code variant.stock.decrease.success} — 성공 카운터</li>
 *   <li>{@code variant.stock.decrease.retry} — 재시도 발생 카운터</li>
 *   <li>{@code variant.stock.decrease.failure} — 한계 초과 실패 카운터</li>
 * </ul>
 */
@Service
public class DecreaseVariantStockService implements DecreaseVariantStockUseCase {

    private static final Logger log = LoggerFactory.getLogger(DecreaseVariantStockService.class);
    static final int MAX_ATTEMPTS = 5;
    static final long INITIAL_BACKOFF_MS = 10;

    private final LoadProductVariantPort loadPort;
    private final SaveProductVariantPort savePort;
    private final TransactionTemplate transactionTemplate;
    private final Counter successCounter;
    private final Counter retryCounter;
    private final Counter failureCounter;

    public DecreaseVariantStockService(LoadProductVariantPort loadPort,
                                       SaveProductVariantPort savePort,
                                       TransactionTemplate transactionTemplate,
                                       MeterRegistry meterRegistry) {
        this.loadPort = loadPort;
        this.savePort = savePort;
        this.transactionTemplate = transactionTemplate;
        this.successCounter = Counter.builder("variant.stock.decrease.success")
                .description("Variant 재고 차감 성공 누적")
                .register(meterRegistry);
        this.retryCounter = Counter.builder("variant.stock.decrease.retry")
                .description("Optimistic Lock 충돌로 재시도된 누적 횟수")
                .register(meterRegistry);
        this.failureCounter = Counter.builder("variant.stock.decrease.failure")
                .description("재시도 한계 초과 실패 누적")
                .register(meterRegistry);
    }

    @Override
    public ProductVariant decrease(Long variantId, int quantity) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ProductVariant result = transactionTemplate.execute(status -> {
                    ProductVariant variant = loadPort.loadById(variantId)
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "ProductVariant not found: " + variantId));
                    variant.decreaseStock(quantity);
                    return savePort.save(variant);
                });
                successCounter.increment();
                if (attempt > 1) {
                    log.info("[VariantStock] decreased after {} attempts. variantId={}, qty={}",
                            attempt, variantId, quantity);
                }
                return result;
            } catch (OptimisticLockingFailureException | OptimisticLockException e) {
                // ObjectOptimisticLockingFailureException 은 OptimisticLockingFailureException 의 하위라 자동 포함됨
                retryCounter.increment();
                if (attempt == MAX_ATTEMPTS) {
                    failureCounter.increment();
                    throw new StockConcurrencyException(
                            "재시도 " + MAX_ATTEMPTS + " 회 모두 충돌. variantId=" + variantId, e);
                }
                long backoff = INITIAL_BACKOFF_MS << (attempt - 1); // 10, 20, 40, 80, ...
                log.debug("[VariantStock] retry {}/{} after {}ms. variantId={}",
                        attempt, MAX_ATTEMPTS, backoff, variantId);
                sleep(backoff);
            }
        }
        // 도달 불가 — 위 루프에서 항상 return 또는 throw
        throw new IllegalStateException("unreachable");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 외부에서 트랜잭션을 가지고 들어온 환경 (e.g. 결제 트랜잭션 안) 에서 사용할 진입점.
     * 내부에서 {@code REQUIRES_NEW} 로 새 트랜잭션을 열어 재시도가 정상 작동하도록 보장.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProductVariant decreaseInNewTransaction(Long variantId, int quantity) {
        return decrease(variantId, quantity);
    }
}
