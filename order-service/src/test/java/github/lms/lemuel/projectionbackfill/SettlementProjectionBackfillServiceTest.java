package github.lms.lemuel.projectionbackfill;

import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.PublishOrderEventPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadSellerSettlementMetaPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.PublishProductEventPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.sellertier.application.port.out.LoadTierAssignmentPort;
import github.lms.lemuel.sellertier.application.port.out.PublishSellerTierEventPort;
import github.lms.lemuel.sellertier.domain.SellerTierGrade;
import github.lms.lemuel.sellertier.domain.TierAssignment;
import github.lms.lemuel.sellertier.domain.TierChangeReason;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PublishUserEventPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * settlement 프로젝션 백필 (ADR 0020 Phase 4 · ADR 0031 §4).
 *
 * <p>등급 백필이 필요한 이유: 등급 통지는 <b>변경 시점</b>에만 발행되므로, 도입 이전부터 있던 셀러의
 * 등급은 통지가 한 번도 오지 않아 소비측 뷰가 계속 비어 있다. 리포트를 켜기 전에 채워야 한다.
 */
@ExtendWith(MockitoExtension.class)
class SettlementProjectionBackfillServiceTest {

    @Mock LoadUserPort loadUserPort;
    @Mock LoadProductPort loadProductPort;
    @Mock LoadOrderPort loadOrderPort;
    @Mock LoadPaymentPort loadPaymentPort;
    @Mock LoadTierAssignmentPort loadTierAssignmentPort;

    @Mock PublishUserEventPort publishUserEventPort;
    @Mock PublishProductEventPort publishProductEventPort;
    @Mock PublishOrderEventPort publishOrderEventPort;
    @Mock PublishEventPort publishEventPort;
    @Mock PublishSellerTierEventPort publishSellerTierEventPort;
    @Mock LoadSellerSettlementMetaPort loadSellerSettlementMetaPort;

    private SettlementProjectionBackfillService service;

    @BeforeEach
    void setUp() {
        service = new SettlementProjectionBackfillService(
                loadUserPort, loadProductPort, loadOrderPort, loadPaymentPort, loadTierAssignmentPort,
                publishUserEventPort, publishProductEventPort, publishOrderEventPort, publishEventPort,
                publishSellerTierEventPort, loadSellerSettlementMetaPort);
    }

    private void emptyExceptTiers(List<TierAssignment> tiers) {
        when(loadUserPort.findAll()).thenReturn(List.of());
        when(loadProductPort.findAll()).thenReturn(List.of());
        when(loadOrderPort.findAll()).thenReturn(List.of());
        when(loadPaymentPort.findAllCaptured()).thenReturn(List.of());
        when(loadTierAssignmentPort.findAll()).thenReturn(tiers);
    }

    @Test
    @DisplayName("Phase 4 Chunk 3: 기존 user/product/order/payment 를 이벤트로 재발행하고 건수를 반환한다")
    void backfillAll_republishesEachEntityAsEvent() {
        when(loadUserPort.findAll()).thenReturn(List.of(
                User.createWithProfile("a@b.com", "h", UserRole.USER, "n", "010-0000-0000")));
        when(loadProductPort.findAll()).thenReturn(List.of(
                Product.create("원목마루", "desc", new BigDecimal("1000"), 10)));
        when(loadOrderPort.findAll()).thenReturn(List.of(
                Order.create(1L, 2L, new BigDecimal("5000"))));
        when(loadPaymentPort.findAllCaptured()).thenReturn(List.of(
                PaymentDomain.rehydrate(7L, 8L, new BigDecimal("5000"), BigDecimal.ZERO,
                        PaymentStatus.CAPTURED, "CARD", "pg-x",
                        LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now())));
        when(loadSellerSettlementMetaPort.findByPaymentId(any())).thenReturn(Optional.empty());
        when(loadTierAssignmentPort.findAll()).thenReturn(List.of());

        var result = service.backfillAll();

        assertThat(result.users()).isEqualTo(1);
        assertThat(result.products()).isEqualTo(1);
        assertThat(result.orders()).isEqualTo(1);
        assertThat(result.payments()).isEqualTo(1);

        verify(publishUserEventPort, times(1)).publishUserRegistered(any(), any());
        verify(publishProductEventPort, times(1)).publishProductChanged(any(), any());
        verify(publishOrderEventPort, times(1)).publishOrderCreated(any(), any(), any(), any(), any(), any());
        verify(publishEventPort, times(1)).publishPaymentCaptured(any(), any(), any(), any(), any(), any(), any());
    }

    @Test @DisplayName("등급 정본을 셀러마다 재발행하고 건수를 보고한다")
    void republishesTiers() {
        emptyExceptTiers(List.of(
                TierAssignment.initial(7L, SellerTierGrade.VIP, LocalDate.of(2026, 3, 1)),
                TierAssignment.initial(8L, SellerTierGrade.NORMAL, LocalDate.of(2026, 4, 1))));

        var result = service.backfillAll();

        assertThat(result.sellerTiers()).isEqualTo(2);
        verify(publishSellerTierEventPort).publishTierChanged(
                eq(7L), isNull(), eq(SellerTierGrade.VIP), eq(TierChangeReason.BACKFILL),
                eq(LocalDate.of(2026, 3, 1)), isNull());
    }

    @Test @DisplayName("백필은 BACKFILL 사유로 나간다 — 승급으로 읽히면 백필 시각이 등급 변경일로 둔갑한다")
    void usesBackfillReason() {
        emptyExceptTiers(List.of(TierAssignment.initial(7L, SellerTierGrade.VIP, LocalDate.of(2026, 3, 1))));

        service.backfillAll();

        verify(publishSellerTierEventPort).publishTierChanged(
                any(), any(), any(), eq(TierChangeReason.BACKFILL), any(), any());
    }

    @Test @DisplayName("발효일은 정본의 값을 그대로 쓴다 — 백필 실행일로 덮으면 이력이 뭉개진다")
    void keepsOriginalEffectiveFrom() {
        emptyExceptTiers(List.of(TierAssignment.initial(7L, SellerTierGrade.VIP, LocalDate.of(2025, 12, 31))));

        service.backfillAll();

        verify(publishSellerTierEventPort).publishTierChanged(
                any(), any(), any(), any(), eq(LocalDate.of(2025, 12, 31)), any());
    }

    @Test @DisplayName("등급이 없으면 등급 발행은 0건 — 다른 백필은 그대로 돈다")
    void noTiersStillBackfillsRest() {
        emptyExceptTiers(List.of());

        var result = service.backfillAll();

        assertThat(result.sellerTiers()).isZero();
    }
}
