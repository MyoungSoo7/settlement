package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.out.LoadReleasableHoldbackPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ReleaseHoldbackService service() {
        return new ReleaseHoldbackService(loadPort, savePort, registry);
    }

    /** release_date == today 이고 holdback 이 살아있는 releasable 정산 1건 생성. */
    private Settlement releasable(long paymentId) {
        Settlement s = Settlement.createFromPayment(paymentId, paymentId, new BigDecimal("50000"), TODAY);
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
}
