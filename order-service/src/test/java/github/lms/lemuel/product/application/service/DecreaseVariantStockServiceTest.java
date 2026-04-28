package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.application.port.out.SaveProductVariantPort;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.exception.InsufficientStockException;
import github.lms.lemuel.product.domain.exception.StockConcurrencyException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 재시도 로직 단위 테스트.
 *
 * <p>실 PostgreSQL 동시성은 별도 통합 테스트로 검증. 여기서는 OptimisticLockingFailureException
 * 시 N 회 재시도 + 백오프 + 한계 도달 시 StockConcurrencyException 전환 동작만 격리 검증한다.
 */
class DecreaseVariantStockServiceTest {

    private LoadProductVariantPort loadPort;
    private SaveProductVariantPort savePort;
    private DecreaseVariantStockService service;

    @BeforeEach
    void setup() {
        loadPort = mock(LoadProductVariantPort.class);
        savePort = mock(SaveProductVariantPort.class);
        // 트랜잭션을 직접 실행시키는 fake — TransactionCallback 을 그대로 호출
        TransactionTemplate txTemplate = new TransactionTemplate() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        service = new DecreaseVariantStockService(loadPort, savePort, txTemplate, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("정상 차감: 첫 시도에 성공 — 재시도 없음")
    void firstAttemptSucceeds() {
        ProductVariant variant = ProductVariant.rehydrate(1L, 10L, "SKU", "옵션",
                BigDecimal.ZERO, 50, 0L,
                github.lms.lemuel.product.domain.ProductVariantStatus.ACTIVE, null, null);
        when(loadPort.loadById(1L)).thenReturn(Optional.of(variant));
        when(savePort.save(any())).thenReturn(variant);

        ProductVariant result = service.decrease(1L, 5);

        assertThat(result).isNotNull();
        verify(loadPort, times(1)).loadById(1L);
    }

    @Test
    @DisplayName("OptimisticLock 충돌 시 재시도 후 성공")
    void retriesOnOptimisticLock() {
        ProductVariant variant = freshVariant();
        when(loadPort.loadById(1L)).thenReturn(Optional.of(variant));
        // 처음 2번은 충돌, 3번째 성공
        when(savePort.save(any()))
                .thenThrow(new OptimisticLockingFailureException("conflict 1"))
                .thenThrow(new OptimisticLockingFailureException("conflict 2"))
                .thenReturn(variant);

        ProductVariant result = service.decrease(1L, 1);

        assertThat(result).isNotNull();
        verify(loadPort, times(3)).loadById(1L); // 매 재시도마다 다시 로드
        verify(savePort, times(3)).save(any());
    }

    @Test
    @DisplayName("재시도 한계 초과: StockConcurrencyException 으로 전환")
    void exceedsRetryLimit() {
        // 매번 새 variant 인스턴스 반환 (재시도마다 fresh load)
        when(loadPort.loadById(1L)).thenAnswer(inv -> Optional.of(freshVariant()));
        when(savePort.save(any())).thenThrow(new OptimisticLockingFailureException("conflict"));

        assertThatThrownBy(() -> service.decrease(1L, 1))
                .isInstanceOf(StockConcurrencyException.class)
                .hasMessageContaining("재시도");

        verify(savePort, times(DecreaseVariantStockService.MAX_ATTEMPTS)).save(any());
    }

    @Test
    @DisplayName("재고 부족: InsufficientStockException — 재시도 없음 (도메인 검증 실패는 재시도 대상 아님)")
    void insufficientStock_noRetry() {
        ProductVariant variant = ProductVariant.rehydrate(1L, 10L, "SKU", "옵션",
                BigDecimal.ZERO, 1, 0L,
                github.lms.lemuel.product.domain.ProductVariantStatus.ACTIVE, null, null);
        when(loadPort.loadById(1L)).thenReturn(Optional.of(variant));

        assertThatThrownBy(() -> service.decrease(1L, 5))
                .isInstanceOf(InsufficientStockException.class);

        verify(savePort, never()).save(any());
    }

    @Test
    @DisplayName("variant 미존재 시 IllegalArgumentException")
    void unknownVariant() {
        when(loadPort.loadById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decrease(999L, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ProductVariant freshVariant() {
        return ProductVariant.rehydrate(1L, 10L, "SKU", "옵션",
                BigDecimal.ZERO, 50, 0L,
                github.lms.lemuel.product.domain.ProductVariantStatus.ACTIVE, null, null);
    }
}
