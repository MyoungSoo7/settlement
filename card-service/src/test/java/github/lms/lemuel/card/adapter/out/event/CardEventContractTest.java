package github.lms.lemuel.card.adapter.out.event;

import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import github.lms.lemuel.card.domain.LimitChangeResult;
import github.lms.lemuel.card.domain.LimitSnapshot;
import github.lms.lemuel.card.domain.ReputationGrade;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.OutboxJson;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 프로듀서 계약 테스트 (ADR 0024) — card 가 발행하는 이벤트가 shared-common 의 계약 스키마
 * (lemuel.card.account_opened)를 만족해야 한다. 계약 드리프트를 런타임(DLT/무성 null)이 아닌
 * 빌드 시점에 차단한다.
 */
@ExtendWith(MockitoExtension.class)
class CardEventContractTest {

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    CardEventPublisherAdapter publisher;

    @BeforeEach
    void setUp() {
        publisher = new CardEventPublisherAdapter(saveOutboxEventPort, OutboxJson.mapper());
    }

    private CardAccount activeAccount() {
        CardAccount account = CardAccount.builder()
                .id(5001L)
                .organizationId(3001L)
                .sellerId("777")
                .status(github.lms.lemuel.card.domain.CardAccountStatus.SCREENING)
                .masterLimit(BigDecimal.ZERO)
                .build();
        account.activate(new BigDecimal("700000"), new LimitSnapshot(
                new BigDecimal("800000"), new BigDecimal("200000"),
                new BigDecimal("0.70"), ReputationGrade.B,
                "floor((sellerPayable + holdbackPayable) x R x H)"));
        return account;
    }

    @Test
    @DisplayName("account_opened 페이로드는 계약을 만족하고 금액이 문자열이다")
    void accountOpened_satisfiesContract() {
        publisher.publishAccountOpened(activeAccount());

        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        String payload = outboxCaptor.getValue().getPayload();
        EventContractValidator.assertValid("lemuel.card.account_opened", payload);
        assertThat(payload).contains("\"masterLimit\":\"700000\"");
    }

    /**
     * 토픽 라우팅은 aggregateType + eventType 에서 파생된다(KafkaOutboxPublisher.resolveTopic).
     * "Card" + "CardAccountOpened" → lemuel.card.account_opened — 이 두 문자열이 곧 토픽이라
     * 오타 한 글자가 소비자 없는 토픽으로 조용히 새는 경로가 된다. 그래서 값 자체를 고정한다.
     */
    @Test
    @DisplayName("aggregateType·eventType 이 lemuel.card.account_opened 로 라우팅되는 값이다")
    void routingKeysArePinned() {
        publisher.publishAccountOpened(activeAccount());

        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        OutboxEvent event = outboxCaptor.getValue();
        assertThat(event.getAggregateType()).isEqualTo("Card");
        assertThat(event.getEventType()).isEqualTo("CardAccountOpened");
        assertThat(event.getAggregateId()).isEqualTo("5001");
    }

    private static Card issuedCard() {
        return Card.builder()
                .id(9001L)
                .cardAccountId(5001L)
                .holderUserId(888L)
                .maskedCardNo("****-****-****-1234")
                .subLimit(new BigDecimal("100000"))
                .status(github.lms.lemuel.card.domain.CardStatus.ISSUED)
                .build();
    }

    @Test
    @DisplayName("issued 페이로드는 계약을 만족하고 조직 식별자·마스킹 번호를 싣는다")
    void issued_satisfiesContract() {
        publisher.publishIssued(issuedCard(), activeAccount());

        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        String payload = outboxCaptor.getValue().getPayload();
        EventContractValidator.assertValid("lemuel.card.issued", payload);
        assertThat(payload).contains("\"subLimit\":\"100000\"");
        assertThat(payload).contains("\"organizationId\":3001");
    }

    /**
     * 발급 이벤트의 파티션 키는 카드가 아니라 <b>카드계정</b>이다. cardId 로 잡으면 같은 계정의
     * 발급·한도변경이 서로 다른 파티션에 흩어져 소비자가 한도 배분 순서를 뒤집힌 채로 본다.
     */
    @Test
    @DisplayName("issued 는 cardAccountId 로 파티셔닝된다 — cardId 가 아니다")
    void issuedIsPartitionedByAccount() {
        publisher.publishIssued(issuedCard(), activeAccount());

        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        OutboxEvent event = outboxCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo("CardIssued");
        assertThat(event.getAggregateId()).isEqualTo("5001");
    }

    @Test
    @DisplayName("서브한도 변경 페이로드는 계약을 만족하고 이전·이후 금액을 문자열로 싣는다")
    void subLimitChanged_satisfiesContract() {
        publisher.publishSubLimitChanged(issuedCard(), activeAccount(), new BigDecimal("50000"));

        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        String payload = outboxCaptor.getValue().getPayload();
        EventContractValidator.assertValid("lemuel.card.limit_changed", payload);
        assertThat(payload).contains("\"previousLimit\":\"50000\"");
        assertThat(payload).contains("\"newLimit\":\"100000\"");
        assertThat(payload).contains("\"scope\":\"SUB\"");
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("CardLimitChanged");
    }

    /**
     * 마스터 한도 변경도 같은 토픽으로 나가므로 {@code scope} 가 유일한 구분자다.
     * 소비자가 "어느 한도가 바뀌었나"를 페이로드 모양 추측으로 판단하게 두지 않는다.
     */
    @Test
    @DisplayName("서브한도 변경의 scope 는 SUB 이고 cardId 가 실린다")
    void subLimitChangedCarriesScopeAndCardId() {
        publisher.publishSubLimitChanged(issuedCard(), activeAccount(), new BigDecimal("50000"));

        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getPayload()).contains("\"cardId\":9001");
        assertThat(outboxCaptor.getValue().getAggregateId()).isEqualTo("5001");
    }

    @Test
    @DisplayName("상태 변경 페이로드는 계약을 만족하고 이전 상태·사유를 남긴다")
    void statusChanged_satisfiesContract() {
        publisher.publishStatusChanged(issuedCard(), activeAccount(),
                github.lms.lemuel.card.domain.CardStatus.SUSPENDED, "휴직");

        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        String payload = outboxCaptor.getValue().getPayload();
        EventContractValidator.assertValid("lemuel.card.status_changed", payload);
        assertThat(payload).contains("\"previousStatus\":\"SUSPENDED\"");
        assertThat(payload).contains("\"newStatus\":\"ISSUED\"");
        assertThat(payload).contains("\"reason\":\"휴직\"");
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("CardStatusChanged");
    }

    /**
     * 마스터 한도 변경(일 1회 재산정)은 서브한도 변경과 <b>같은 토픽</b>을 쓴다. 그래서 계약을
     * 만족하는 것만으로는 부족하고, scope=MASTER 와 cardId=null 이 함께 나가야 소비자가
     * "계정 전체의 변경"임을 페이로드 모양 추측 없이 판단할 수 있다.
     */
    @Test
    @DisplayName("마스터 한도 변경 페이로드는 계약을 만족하고 scope=MASTER·cardId=null 이다")
    void masterLimitChanged_satisfiesContract() {
        publisher.publishMasterLimitChanged(activeAccount(), new BigDecimal("500000"),
                new LimitChangeResult(new BigDecimal("700000"), false));

        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        String payload = outboxCaptor.getValue().getPayload();
        EventContractValidator.assertValid("lemuel.card.limit_changed", payload);
        assertThat(payload).contains("\"scope\":\"MASTER\"");
        assertThat(payload).contains("\"cardId\":null");
        assertThat(payload).contains("\"previousLimit\":\"500000\"");
        assertThat(payload).contains("\"newLimit\":\"700000\"");
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("CardLimitChanged");
        assertThat(outboxCaptor.getValue().getAggregateId()).isEqualTo("5001");
    }

    /**
     * 하향이 Σ서브한도에 걸려 덜 내려갔다는 사실({@code clamped})은 페이로드에 남아야 한다.
     * 이것이 없으면 소비자·감사 쪽은 "왜 요청한 값과 다른 한도가 적용됐나"를 로그로만 추적하게 된다.
     */
    @Test
    @DisplayName("클램프된 하향은 clamped=true 로 표시된다")
    void clampedDowngradeIsFlagged() {
        publisher.publishMasterLimitChanged(activeAccount(), new BigDecimal("700000"),
                new LimitChangeResult(new BigDecimal("300000"), true));

        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getPayload()).contains("\"clamped\":true");
    }

    /**
     * 계정 상태 변경은 카드 상태 변경과 <b>다른 토픽</b>이다 — 상태 enum 도 소비자도 다르다.
     * 재산정 강등은 한도를 0 이 아니라 Σ서브한도로 클램프한 채 정지시키므로 masterLimit 을 함께 싣는다.
     */
    @Test
    @DisplayName("계정 상태 변경 페이로드는 계약을 만족하고 잔여 한도·사유를 남긴다")
    void accountStatusChanged_satisfiesContract() {
        CardAccount account = activeAccount();
        account.suspend();

        publisher.publishAccountStatusChanged(account, CardAccountStatus.ACTIVE,
                "평판 등급 E 는 카드 발급 대상이 아닙니다.");

        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        String payload = outboxCaptor.getValue().getPayload();
        EventContractValidator.assertValid("lemuel.card.account_status_changed", payload);
        assertThat(payload).contains("\"previousStatus\":\"ACTIVE\"");
        assertThat(payload).contains("\"newStatus\":\"SUSPENDED\"");
        assertThat(payload).contains("\"masterLimit\":\"700000\"");
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("CardAccountStatusChanged");
        assertThat(outboxCaptor.getValue().getAggregateId()).isEqualTo("5001");
    }
}
