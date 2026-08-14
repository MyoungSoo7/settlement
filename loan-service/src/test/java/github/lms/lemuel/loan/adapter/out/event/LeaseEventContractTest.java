package github.lms.lemuel.loan.adapter.out.event;

import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.OutboxJson;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.loan.domain.AssetFinanceType;
import github.lms.lemuel.loan.domain.Borrower;
import github.lms.lemuel.loan.domain.LeaseContract;
import github.lms.lemuel.loan.domain.LeaseSchedule;
import github.lms.lemuel.loan.domain.LeaseStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 프로듀서 계약 테스트 (ADR 0024) — 리스 개시 이벤트가 shared-common 의 계약 스키마를 통과해야 한다.
 *
 * <p>소비측이 아직 없더라도(발행 전용 상태) 계약을 먼저 고정한다 — 나중에 소비자가 붙을 때 이미 발행된
 * 이벤트들의 표현이 흔들리면 재처리·백필이 불가능해지기 때문이다.
 */
@ExtendWith(MockitoExtension.class)
class LeaseEventContractTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 13, 0, 0, 0, 0, ZoneOffset.UTC);

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    LeaseEventPublisherAdapter publisher;

    @BeforeEach
    void setUp() {
        publisher = new LeaseEventPublisherAdapter(saveOutboxEventPort, OutboxJson.mapper());
    }

    private static LeaseContract activeContract(AssetFinanceType type, String residual) {
        LeaseSchedule schedule = LeaseSchedule.of(type, new BigDecimal("30000000"),
                BigDecimal.ZERO, new BigDecimal("3000000"), new BigDecimal(residual), 36, new BigDecimal("6.0"));
        return LeaseContract.reconstitute(4242L, Borrower.corporate(8484L, "㈜테스트", "1234567890"),
                type, "지게차 3톤", schedule, LeaseStatus.ACTIVE, 0, NOW, NOW, null);
    }

    private OutboxEvent savedEvent() {
        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        return outboxCaptor.getValue();
    }

    @Test
    @DisplayName("LeaseActivated 페이로드는 lemuel.loan.lease_activated 계약을 만족한다")
    void leaseActivated_satisfiesContract() {
        publisher.publishActivated(activeContract(AssetFinanceType.FINANCE_LEASE, "6000000"));

        EventContractValidator.assertValid("lemuel.loan.lease_activated", savedEvent().getPayload());
    }

    @Test
    @DisplayName("잔존가치 0(할부)도 계약을 만족한다 — 비음수 패턴이라 0 이 허용된다")
    void installmentWithZeroResidual_satisfiesContract() {
        LeaseSchedule schedule = LeaseSchedule.of(AssetFinanceType.INSTALLMENT,
                new BigDecimal("30000000"), new BigDecimal("6000000"), BigDecimal.ZERO,
                BigDecimal.ZERO, 36, new BigDecimal("6.0"));
        LeaseContract contract = LeaseContract.reconstitute(1L, Borrower.individual(2L, "홍길동"),
                AssetFinanceType.INSTALLMENT, "승용차", schedule, LeaseStatus.ACTIVE, 0, NOW, NOW, null);

        publisher.publishActivated(contract);

        EventContractValidator.assertValid("lemuel.loan.lease_activated", savedEvent().getPayload());
    }

    @Test
    @DisplayName("금액은 문자열로 나간다 — 소비측 double 역직렬화로 원 단위가 흔들리지 않게")
    void amountsAreSerializedAsStrings() {
        publisher.publishActivated(activeContract(AssetFinanceType.FINANCE_LEASE, "6000000"));

        assertThat(savedEvent().getPayload())
                .contains("\"financedAmount\":\"27000000\"")
                .contains("\"residualValue\":\"6000000\"")
                .doesNotContain("\"financedAmount\":27000000");
    }

    @Test
    @DisplayName("Outbox 라우팅 좌표가 lemuel.loan.lease_activated 로 해석되는 값이다")
    void outboxRoutingCoordinates() {
        publisher.publishActivated(activeContract(AssetFinanceType.OPERATING_LEASE, "6000000"));

        OutboxEvent event = savedEvent();
        assertThat(event.getAggregateType()).isEqualTo("Loan");
        assertThat(event.getEventType()).isEqualTo("LeaseActivated");
        assertThat(event.getAggregateId()).isEqualTo("4242");
    }
}
