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
 * 컨슈머 계약 테스트 (ADR 0024) — 포인트 이벤트 5종의 <b>정본 샘플</b>을 실제 컨슈머에 통과시켜
 * 이벤트→분개 매핑이 계약 값 그대로 적재되는지 검증한다.
 *
 * <p>특히 {@code point.used} 의 대변이 <b>현금</b>인지를 고정한다. 이 상계가 빠지면
 * settlement.created 가 전기한 현금 유입이 이중 계상되어 시산표가 조용히 부풀어 오른다.
 */
@ExtendWith(MockitoExtension.class)
class PointLedgerConsumerTest {

    @Mock RecordAccountEntryUseCase recordAccountEntryUseCase;
    @Mock ProcessedEventRepository processedEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PointLedgerConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PointLedgerConsumer(recordAccountEntryUseCase, processedEventRepository, objectMapper);
    }

    private static ConsumerRecord<String, String> recordOf(String topic, String json) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, null, json);
        record.headers().add("event_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private static ConsumerRecord<String, String> canonical(String topic) {
        return recordOf(topic, EventContractValidator.canonicalSample(topic));
    }

    private AccountEntry consumeAndCapture(String topic) {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        consumer.onPointEvent(canonical(topic), mock(Acknowledgment.class));
        ArgumentCaptor<AccountEntry> captor = ArgumentCaptor.forClass(AccountEntry.class);
        verify(recordAccountEntryUseCase).record(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("point.charged 정본 샘플 → DR CASH / CR POINT_LIABILITY (현금이 실제로 들어온 몫)")
    void charged() {
        AccountEntry entry = consumeAndCapture("lemuel.point.charged");

        assertThat(entry.getOwnerType()).isEqualTo(OwnerType.CUSTOMER);
        assertThat(entry.getOwnerId()).isEqualTo("42");
        assertThat(entry.getDebitAccount()).isEqualTo(GlAccount.CASH);
        assertThat(entry.getCreditAccount()).isEqualTo(GlAccount.POINT_LIABILITY);
        assertThat(entry.getRefType()).isEqualTo("POINT_CHARGED");
        assertThat(entry.getAmount()).isEqualByComparingTo(new BigDecimal("100000"));
    }

    @Test
    @DisplayName("point.granted 정본 샘플 → DR POINT_PROMOTION_EXPENSE / CR POINT_LIABILITY (회사가 얹은 몫)")
    void granted() {
        AccountEntry entry = consumeAndCapture("lemuel.point.granted");

        assertThat(entry.getDebitAccount()).isEqualTo(GlAccount.POINT_PROMOTION_EXPENSE);
        assertThat(entry.getCreditAccount()).isEqualTo(GlAccount.POINT_LIABILITY);
        assertThat(entry.getRefType()).isEqualTo("POINT_GRANTED");
    }

    @Test
    @DisplayName("point.used 정본 샘플 → DR POINT_LIABILITY / CR CASH — settlement 이 가정한 현금 유입을 상계한다")
    void used() {
        AccountEntry entry = consumeAndCapture("lemuel.point.used");

        assertThat(entry.getDebitAccount()).isEqualTo(GlAccount.POINT_LIABILITY);
        assertThat(entry.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(entry.getRefType()).isEqualTo("POINT_USED");
        // 자연키는 원장 엔트리 식별자 — 같은 사용을 두 번 전기하지 않는다.
        assertThat(entry.getRefId()).isEqualTo("900");
    }

    @Test
    @DisplayName("point.restored 정본 샘플 → DR CASH / CR POINT_LIABILITY (사용의 대칭)")
    void restored() {
        AccountEntry entry = consumeAndCapture("lemuel.point.restored");

        assertThat(entry.getDebitAccount()).isEqualTo(GlAccount.CASH);
        assertThat(entry.getCreditAccount()).isEqualTo(GlAccount.POINT_LIABILITY);
        assertThat(entry.getRefType()).isEqualTo("POINT_RESTORED");
    }

    @Test
    @DisplayName("point.expired 정본 샘플 → DR POINT_LIABILITY / CR POINT_BREAKAGE_INCOME (소멸이익 인식)")
    void expired() {
        AccountEntry entry = consumeAndCapture("lemuel.point.expired");

        assertThat(entry.getDebitAccount()).isEqualTo(GlAccount.POINT_LIABILITY);
        assertThat(entry.getCreditAccount()).isEqualTo(GlAccount.POINT_BREAKAGE_INCOME);
        assertThat(entry.getRefType()).isEqualTo("POINT_EXPIRED");
    }

    @Test
    @DisplayName("point.revoked 정본 샘플 → DR POINT_LIABILITY / CR POINT_PROMOTION_EXPENSE (판촉비 환입)")
    void revoked() {
        AccountEntry entry = consumeAndCapture("lemuel.point.revoked");

        assertThat(entry.getDebitAccount()).isEqualTo(GlAccount.POINT_LIABILITY);
        // 소멸은 이익(BREAKAGE_INCOME), 취소는 비용 환입 — 둘을 합치면 손익에서 분리할 수 없다.
        assertThat(entry.getCreditAccount()).isEqualTo(GlAccount.POINT_PROMOTION_EXPENSE);
        assertThat(entry.getRefType()).isEqualTo("POINT_REVOKED");
    }

    @Test
    @DisplayName("이미 처리한 이벤트는 다시 전기하지 않는다 — 멱등 2단")
    void alreadyProcessedEventIsSkipped() {
        when(processedEventRepository.existsById(any())).thenReturn(true);

        consumer.onPointEvent(canonical("lemuel.point.used"), mock(Acknowledgment.class));

        verify(recordAccountEntryUseCase, never()).record(any());
    }

    @Test
    @DisplayName("카탈로그에 없는 토픽이 닿으면 조용히 넘기지 않고 실패한다 — 분개 누락은 침묵보다 낫다")
    void unknownTopicFails() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        ConsumerRecord<String, String> record =
                recordOf("lemuel.point.unknown", EventContractValidator.canonicalSample("lemuel.point.used"));

        assertThatThrownBy(() -> consumer.onPointEvent(record, mock(Acknowledgment.class)))
                .isInstanceOf(IllegalStateException.class);
        verify(recordAccountEntryUseCase, never()).record(any());
    }
}
