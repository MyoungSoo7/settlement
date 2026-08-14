package github.lms.lemuel.deposit.adapter.out.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.deposit.domain.DepositEntry;
import github.lms.lemuel.deposit.domain.DepositEntryType;
import github.lms.lemuel.deposit.domain.DepositHold;
import github.lms.lemuel.deposit.domain.DepositHoldStatus;
import github.lms.lemuel.deposit.domain.DepositHolderType;
import github.lms.lemuel.deposit.domain.DepositOffsetShortfall;
import github.lms.lemuel.deposit.domain.DepositShortfallStatus;
import github.lms.lemuel.deposit.domain.SellerDepositAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 예치금 이벤트 → Outbox 기록.
 *
 * <p>여기서 지키는 것 두 가지다. ① 발행은 Kafka 직접 전송이 아니라 <b>Outbox 적재</b>여야 한다
 * (DB 트랜잭션과 원자성). ② 금액은 지수표기가 섞이지 않는 {@code toPlainString} 으로 실려야 한다
 * (DATA-STANDARD N5) — 소비 측이 문자열을 그대로 BigDecimal 로 되읽기 때문이다.
 */
class DepositEventPublisherAdapterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 14, 10, 0);

    private SaveOutboxEventPort saveOutboxEventPort;
    private DepositEventPublisherAdapter adapter;

    @BeforeEach
    void setUp() {
        saveOutboxEventPort = mock(SaveOutboxEventPort.class);
        adapter = new DepositEventPublisherAdapter(saveOutboxEventPort, new ObjectMapper());
    }

    private static SellerDepositAccount account() {
        return SellerDepositAccount.rehydrate(1L, 7L,
                new BigDecimal("3000000.00"), new BigDecimal("500000.00"), new BigDecimal("3500000.00"),
                3L, NOW, NOW);
    }

    private OutboxEvent captured() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(saveOutboxEventPort).save(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadOf(OutboxEvent event) throws Exception {
        return new ObjectMapper().readValue(event.getPayload(), Map.class);
    }

    @Test
    @DisplayName("잔고 변경 — aggregateType=Deposit, 키는 sellerId, 금액은 plain string")
    void publishesBalanceChanged() throws Exception {
        adapter.publishBalanceChanged(account(), "settlement.confirmed");

        OutboxEvent event = captured();
        assertThat(event.getAggregateType()).isEqualTo("Deposit");
        assertThat(event.getAggregateId()).isEqualTo("7");
        assertThat(event.getEventType()).isEqualTo("DepositBalanceChanged");

        Map<String, Object> payload = payloadOf(event);
        assertThat(payload.get("sellerId")).isEqualTo(7);
        assertThat(payload.get("available")).isEqualTo("3000000.00");
        assertThat(payload.get("locked")).isEqualTo("500000.00");
        assertThat(payload.get("total")).isEqualTo("3500000.00");
        assertThat(payload.get("triggerEventType")).isEqualTo("settlement.confirmed");
    }

    @Test
    @DisplayName("hold 설정 — 홀더 종류·참조와 잔고 스냅샷을 함께 싣는다")
    void publishesHoldPlaced() throws Exception {
        DepositHold hold = DepositHold.rehydrate(11L, 1L, DepositHolderType.LOAN_DISBURSEMENT, "LOAN-1",
                new BigDecimal("500000.00"), new BigDecimal("500000.00"),
                DepositHoldStatus.ACTIVE, NOW.plusDays(1), NOW, NOW, 0L);

        adapter.publishHoldPlaced(hold, account());

        OutboxEvent event = captured();
        assertThat(event.getEventType()).isEqualTo("DepositHoldPlaced");
        Map<String, Object> payload = payloadOf(event);
        assertThat(payload.get("holdId")).isEqualTo(11);
        assertThat(payload.get("holderType")).isEqualTo("LOAN_DISBURSEMENT");
        assertThat(payload.get("holderReference")).isEqualTo("LOAN-1");
        assertThat(payload.get("amount")).isEqualTo("500000.00");
        assertThat(payload.get("available")).isEqualTo("3000000.00");
    }

    @Test
    @DisplayName("hold 해제 — 남은 금액을 releasedAmount 로 싣는다")
    void publishesHoldReleased() throws Exception {
        DepositHold hold = DepositHold.rehydrate(11L, 1L, DepositHolderType.CARD_AUTHORIZATION, "CARD-AUTH-9",
                new BigDecimal("500000.00"), new BigDecimal("200000.00"),
                DepositHoldStatus.RELEASED, null, NOW, NOW, 1L);

        adapter.publishHoldReleased(hold, account());

        Map<String, Object> payload = payloadOf(captured());
        assertThat(payload.get("releasedAmount")).isEqualTo("200000.00");
        assertThat(payload.get("holderType")).isEqualTo("CARD_AUTHORIZATION");
    }

    @Test
    @DisplayName("상계 적용 — 참조·상계순번·근거 hold 를 실어 회수 근거를 남긴다")
    void publishesOffsetApplied() throws Exception {
        DepositEntry entry = DepositEntry.rehydrate(21L, 1L, DepositEntryType.OFFSET,
                new BigDecimal("120000.00"), "PAYOUT-1", "PAYOUT", 2, 11L, NOW);

        adapter.publishOffsetApplied(entry, account());

        OutboxEvent event = captured();
        assertThat(event.getEventType()).isEqualTo("DepositOffsetApplied");
        Map<String, Object> payload = payloadOf(event);
        assertThat(payload.get("entryId")).isEqualTo(21);
        assertThat(payload.get("amount")).isEqualTo("120000.00");
        assertThat(payload.get("referenceId")).isEqualTo("PAYOUT-1");
        assertThat(payload.get("referenceType")).isEqualTo("PAYOUT");
        assertThat(payload.get("offsetSequence")).isEqualTo(2);
        assertThat(payload.get("sourceHoldId")).isEqualTo(11);
    }

    @Test
    @DisplayName("상계 부족분 — 요청·적용·부족 금액 3종과 발생시각을 싣는다")
    void publishesOffsetShortfall() throws Exception {
        DepositOffsetShortfall shortfall = DepositOffsetShortfall.rehydrate(31L, 7L,
                DepositHolderType.LOAN_DISBURSEMENT, "LOAN-1",
                new BigDecimal("300000.00"), new BigDecimal("120000.00"), new BigDecimal("180000.00"),
                DepositShortfallStatus.OPEN, 11L,
                OffsetDateTime.of(2026, 8, 14, 1, 0, 0, 0, ZoneOffset.UTC));

        adapter.publishOffsetShortfall(shortfall);

        OutboxEvent event = captured();
        assertThat(event.getAggregateId()).isEqualTo("7");
        assertThat(event.getEventType()).isEqualTo("DepositOffsetShortfall");
        Map<String, Object> payload = payloadOf(event);
        assertThat(payload.get("requestedAmount")).isEqualTo("300000.00");
        assertThat(payload.get("appliedAmount")).isEqualTo("120000.00");
        assertThat(payload.get("shortfallAmount")).isEqualTo("180000.00");
        assertThat((String) payload.get("occurredAt")).startsWith("2026-08-14T01:00");
    }

    @Test
    @DisplayName("발행은 Kafka 직접 전송이 아니라 Outbox 적재로만 이뤄진다")
    void alwaysGoesThroughOutbox() {
        adapter.publishBalanceChanged(account(), "payout.completed");

        verify(saveOutboxEventPort).save(any(OutboxEvent.class));
    }
}
