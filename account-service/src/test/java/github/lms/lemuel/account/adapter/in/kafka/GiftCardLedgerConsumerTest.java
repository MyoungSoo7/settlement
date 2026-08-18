package github.lms.lemuel.account.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 컨슈머 계약 테스트 (ADR 0024) — 기프트카드 이벤트 4종의 정본 샘플을 실제 컨슈머에 통과시킨다.
 *
 * <p>특히 상품권 부채가 <b>포인트 부채와 다른 계정</b>에 적재되는지를 고정한다. 둘이 한 계정에
 * 뭉치면 시산표에서 나눌 방법이 없다.
 */
@ExtendWith(MockitoExtension.class)
class GiftCardLedgerConsumerTest {

    @Mock RecordAccountEntryUseCase recordAccountEntryUseCase;
    @Mock ProcessedEventRepository processedEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GiftCardLedgerConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new GiftCardLedgerConsumer(recordAccountEntryUseCase, processedEventRepository, objectMapper);
    }

    private static ConsumerRecord<String, String> recordOf(String topic, String json) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, null, json);
        record.headers().add("event_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private AccountEntry consumeAndCapture(String topic) {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        consumer.onGiftCardEvent(
                recordOf(topic, EventContractValidator.canonicalSample(topic)), mock(Acknowledgment.class));
        ArgumentCaptor<AccountEntry> captor = ArgumentCaptor.forClass(AccountEntry.class);
        verify(recordAccountEntryUseCase).record(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("giftcard.registered → DR GIFT_CARD_PROMOTION_EXPENSE / CR GIFT_CARD_LIABILITY (무상 발행분)")
    void registered() {
        AccountEntry entry = consumeAndCapture("lemuel.giftcard.registered");

        assertThat(entry.getOwnerType()).isEqualTo(OwnerType.CUSTOMER);
        assertThat(entry.getOwnerId()).isEqualTo("42");
        assertThat(entry.getDebitAccount()).isEqualTo(GlAccount.GIFT_CARD_PROMOTION_EXPENSE);
        assertThat(entry.getCreditAccount()).isEqualTo(GlAccount.GIFT_CARD_LIABILITY);
        assertThat(entry.getRefType()).isEqualTo("GIFTCARD_REGISTERED");
        assertThat(entry.getAmount()).isEqualByComparingTo(new BigDecimal("50000"));
    }

    @Test
    @DisplayName("giftcard.used → DR GIFT_CARD_LIABILITY / CR CASH — 정산이 가정한 현금 유입을 상계한다")
    void used() {
        AccountEntry entry = consumeAndCapture("lemuel.giftcard.used");

        assertThat(entry.getDebitAccount()).isEqualTo(GlAccount.GIFT_CARD_LIABILITY);
        assertThat(entry.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(entry.getRefType()).isEqualTo("GIFTCARD_USED");
        assertThat(entry.getRefId()).isEqualTo("901");
    }

    @Test
    @DisplayName("giftcard.restored → DR CASH / CR GIFT_CARD_LIABILITY (사용의 대칭)")
    void restored() {
        AccountEntry entry = consumeAndCapture("lemuel.giftcard.restored");

        assertThat(entry.getDebitAccount()).isEqualTo(GlAccount.CASH);
        assertThat(entry.getCreditAccount()).isEqualTo(GlAccount.GIFT_CARD_LIABILITY);
        assertThat(entry.getRefType()).isEqualTo("GIFTCARD_RESTORED");
    }

    @Test
    @DisplayName("giftcard.expired → DR GIFT_CARD_LIABILITY / CR GIFT_CARD_BREAKAGE_INCOME (소멸이익)")
    void expired() {
        AccountEntry entry = consumeAndCapture("lemuel.giftcard.expired");

        assertThat(entry.getDebitAccount()).isEqualTo(GlAccount.GIFT_CARD_LIABILITY);
        assertThat(entry.getCreditAccount()).isEqualTo(GlAccount.GIFT_CARD_BREAKAGE_INCOME);
        assertThat(entry.getRefType()).isEqualTo("GIFTCARD_EXPIRED");
    }

    @Test
    @DisplayName("상품권 부채는 포인트 부채와 다른 계정에 쌓인다 — 시산표에서 분리 가능해야 한다")
    void liabilityIsSeparateFromPoint() {
        AccountEntry entry = consumeAndCapture("lemuel.giftcard.used");

        assertThat(entry.getDebitAccount()).isNotEqualTo(GlAccount.POINT_LIABILITY);
    }

    @Test
    @DisplayName("이미 처리한 이벤트는 다시 전기하지 않는다 — 멱등 2단")
    void alreadyProcessedEventIsSkipped() {
        when(processedEventRepository.existsById(any())).thenReturn(true);

        consumer.onGiftCardEvent(recordOf("lemuel.giftcard.used",
                EventContractValidator.canonicalSample("lemuel.giftcard.used")), mock(Acknowledgment.class));

        verify(recordAccountEntryUseCase, never()).record(any());
    }

    @Test
    @DisplayName("카탈로그에 없는 토픽이 닿으면 조용히 넘기지 않고 실패한다")
    void unknownTopicFails() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        ConsumerRecord<String, String> record = recordOf("lemuel.giftcard.unknown",
                EventContractValidator.canonicalSample("lemuel.giftcard.used"));

        assertThatThrownBy(() -> consumer.onGiftCardEvent(record, mock(Acknowledgment.class)))
                .isInstanceOf(IllegalStateException.class);
        verify(recordAccountEntryUseCase, never()).record(any());
    }
}
