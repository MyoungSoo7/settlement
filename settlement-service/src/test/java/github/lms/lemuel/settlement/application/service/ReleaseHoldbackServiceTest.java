package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.payout.application.port.in.RequestPayoutUseCase;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.settlement.application.port.out.LoadReleasableHoldbackPort;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementDomainEventPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReleaseHoldbackServiceTest {

    private static final int BATCH_SIZE = 100;
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 14);

    @Mock LoadReleasableHoldbackPort loadPort;
    @Mock SaveSettlementPort savePort;
    @Mock LoadSellerIdPort loadSellerIdPort;
    @Mock RequestPayoutUseCase requestPayoutUseCase;
    @Mock PublishSettlementDomainEventPort publishEventPort;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ReleaseHoldbackService service() {
        return new ReleaseHoldbackService(loadPort, savePort, loadSellerIdPort, requestPayoutUseCase,
                publishEventPort, registry);
    }

    /** release_date == today 이고 holdback 이 살아있는 releasable 정산 1건 생성. */
    private Settlement releasable(long paymentId) {
        Settlement s = Settlement.createFromPayment(paymentId, paymentId, new BigDecimal("50000"), TODAY);
        s.assignId(paymentId); // 영속 정산은 PK 를 갖는다(발행이 s.getId() 를 primitive long 으로 쓰므로 필수)
        s.applyHoldback(new BigDecimal("0.30"), TODAY); // releaseDate=today → 오늘 해제 가능
        return s;
    }

    private List<Settlement> releasableBatch(int count) {
        List<Settlement> batch = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            batch.add(releasable(i + 1L));
        }
        return batch;
    }

    @Test
    void 해제_대상이_없으면_0건이고_카운터도_증가하지_않는다() {
        when(loadPort.findReleasableOn(TODAY, BATCH_SIZE)).thenReturn(List.of());

        int released = service().releaseAllDueOn(TODAY);

        assertThat(released).isZero();
        verify(savePort, never()).save(any());
        // 카운터는 생성자에서 등록되므로 항상 존재하되, 해제 0건이면 증가하지 않는다.
        assertThat(registry.get("settlement.holdback.released").counter().count()).isZero();
    }

    @Test
    void 한_배치_미만이면_전건_해제하고_카운터를_올린다() {
        List<Settlement> batch = releasableBatch(3);
        when(loadPort.findReleasableOn(TODAY, BATCH_SIZE)).thenReturn(batch);

        int released = service().releaseAllDueOn(TODAY);

        assertThat(released).isEqualTo(3);
        verify(savePort, times(3)).save(any(Settlement.class));
        assertThat(batch).allSatisfy(s -> assertThat(s.isHoldbackReleased()).isTrue());
        assertThat(registry.get("settlement.holdback.released").counter().count()).isEqualTo(3.0);
    }

    @Test
    void 해제_시_잔여_보류액으로_HOLDBACK_RELEASE_Payout_을_생성한다() {
        // 결제 50,000 / 수수료 3% 1,500 / net 48,500 / holdback 30% → 14,550
        Settlement s = releasable(1L);
        when(loadPort.findReleasableOn(TODAY, BATCH_SIZE)).thenReturn(List.of(s));
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.of(55L));

        service().releaseAllDueOn(TODAY);

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(requestPayoutUseCase).requestPayoutOfType(
                any(), eq(55L), amount.capture(), eq(PayoutType.HOLDBACK_RELEASE));
        assertThat(amount.getValue()).isEqualByComparingTo("14550");
        // account 로 유보 해제 재분류 이벤트도 같은 금액으로 발행된다.
        ArgumentCaptor<BigDecimal> released = ArgumentCaptor.forClass(BigDecimal.class);
        verify(publishEventPort).publishHoldbackReleased(eq(s.getId()), eq(55L), released.capture());
        assertThat(released.getValue()).isEqualByComparingTo("14550");
    }

    @Test
    void 잔여_보류액이_0이면_HoldbackReleased_를_발행하지_않는다() {
        // holdback 0% → 해제 대상은 맞지만 잔여 보류액 0 → 회계 이벤트·Payout 모두 없음
        Settlement s = Settlement.createFromPayment(1L, 1L, new BigDecimal("50000"), TODAY);
        s.applyHoldback(new BigDecimal("0.00"), TODAY);
        when(loadPort.findReleasableOn(TODAY, BATCH_SIZE)).thenReturn(List.of(s));
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.of(55L));

        service().releaseAllDueOn(TODAY);

        verify(publishEventPort, never()).publishHoldbackReleased(anyLong(), anyLong(), any());
    }

    @Test
    void 판매자_미해석이면_HoldbackReleased_도_발행하지_않는다() {
        Settlement s = releasable(1L);
        when(loadPort.findReleasableOn(TODAY, BATCH_SIZE)).thenReturn(List.of(s));
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.empty());

        service().releaseAllDueOn(TODAY);

        verify(publishEventPort, never()).publishHoldbackReleased(anyLong(), anyLong(), any());
    }

    @Test
    void 판매자_미해석이면_HOLDBACK_RELEASE_Payout_을_생성하지_않는다() {
        Settlement s = releasable(1L);
        when(loadPort.findReleasableOn(TODAY, BATCH_SIZE)).thenReturn(List.of(s));
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.empty());

        service().releaseAllDueOn(TODAY);

        verify(requestPayoutUseCase, never()).requestPayoutOfType(any(), any(), any(), any());
    }

    @Test
    void 가득찬_배치는_다음_페이지를_이어서_처리하고_빈_배치에서_종료한다() {
        List<Settlement> full = releasableBatch(BATCH_SIZE);
        // 첫 호출: 100건(가득참) → 루프 계속, 둘째 호출: 0건 → 종료
        when(loadPort.findReleasableOn(TODAY, BATCH_SIZE)).thenReturn(full, List.of());

        int released = service().releaseAllDueOn(TODAY);

        assertThat(released).isEqualTo(BATCH_SIZE);
        verify(loadPort, times(2)).findReleasableOn(TODAY, BATCH_SIZE);
        verify(savePort, times(BATCH_SIZE)).save(any(Settlement.class));
        assertThat(registry.get("settlement.holdback.released").counter().count())
                .isEqualTo((double) BATCH_SIZE);
    }

    // ---------- 홀드백 해제 미리보기 (dryRun) ----------

    @Test
    @org.junit.jupiter.api.DisplayName("미리보기: 해제 대상 건수와 총액을 집계한다")
    void preview_aggregates() {
        List<Settlement> batch = releasableBatch(3);
        when(loadPort.findReleasableOn(eq(TODAY), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(batch);
        // 홀드백은 결제금액이 아니라 수수료 차감 후 순액 기준이라, 기대값을 손으로 적지 않고
        // 픽스처의 실제 보류액을 합산해 비교한다(수수료율이 바뀌어도 이 단언은 여전히 옳다).
        BigDecimal expected = batch.stream().map(Settlement::getHoldbackAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        var preview = service().previewReleasableOn(TODAY, 100);

        assertThat(preview.count()).isEqualTo(3);
        assertThat(preview.totalAmount()).isEqualByComparingTo(expected);
        assertThat(preview.lines()).hasSize(3);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("미리보기는 해제도 지급요청도 이벤트도 만들지 않는다")
    void preview_changesNothing() {
        when(loadPort.findReleasableOn(eq(TODAY), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(releasableBatch(2));

        service().previewReleasableOn(TODAY, 100);

        verify(savePort, never()).save(any());
        verify(requestPayoutUseCase, never())
                .requestPayoutOfType(anyLong(), anyLong(), any(), any(PayoutType.class));
        org.mockito.Mockito.verifyNoInteractions(publishEventPort);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("미리보기는 드레인 루프를 쓰지 않는다 — 조회는 정확히 1회")
    void preview_doesNotDrain() {
        // 실행 경로는 해제하면서 대상이 줄어 루프가 끝나지만, 미리보기는 아무것도 해제하지 않는다.
        // 같은 루프를 쓰면 같은 배치가 영원히 반환돼 무한 루프가 된다.
        when(loadPort.findReleasableOn(eq(TODAY), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(releasableBatch(BATCH_SIZE));

        var preview = service().previewReleasableOn(TODAY, BATCH_SIZE);

        verify(loadPort, times(1)).findReleasableOn(eq(TODAY), org.mockito.ArgumentMatchers.anyInt());
        assertThat(preview.count()).isEqualTo(BATCH_SIZE);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("한도까지 찼으면 잘렸음을 알린다 — 이게 전부라고 오해하면 안 된다")
    void preview_flagsTruncation() {
        when(loadPort.findReleasableOn(eq(TODAY), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(releasableBatch(10));

        assertThat(service().previewReleasableOn(TODAY, 10).truncated()).isTrue();
        assertThat(service().previewReleasableOn(TODAY, 11).truncated()).isFalse();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("해제 대상이 없으면 0건")
    void preview_empty() {
        when(loadPort.findReleasableOn(eq(TODAY), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        var preview = service().previewReleasableOn(TODAY, 100);

        assertThat(preview.count()).isZero();
        assertThat(preview.totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
