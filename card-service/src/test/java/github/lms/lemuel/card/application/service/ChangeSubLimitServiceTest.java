package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.ChangeSubLimitUseCase.ChangeSubLimitCommand;
import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.LoadCardPort;
import github.lms.lemuel.card.application.port.out.PublishCardEventPort;
import github.lms.lemuel.card.application.port.out.SaveCardPort;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import github.lms.lemuel.card.domain.LimitSnapshot;
import github.lms.lemuel.card.domain.OrgRole;
import github.lms.lemuel.card.domain.ReputationGrade;
import github.lms.lemuel.card.domain.exception.InvalidCardTransitionException;
import github.lms.lemuel.card.domain.exception.SubLimitExceededException;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 서브한도 변경 유스케이스 테스트.
 *
 * <p>여기서 고정하는 계산은 한 줄이다 — <b>비교 대상은 "나를 뺀 합계"</b>다.
 * {@code sum + newLimit} 으로 비교하면 자기 자신의 기존 한도가 이중 계상되어, 마스터 한도에
 * 정확히 맞는 정상 상향이 거부된다. {@code sum - 내 현재 한도 + newLimit} 이어야 한다.
 *
 * <p>락 순서는 발급과 동형이다 — 카드계정을 잠근 <b>뒤에</b> 합계를 재계산한다. 카드 자체는
 * 소속 계정을 알아내야 하므로 락보다 먼저 읽지만, 그 사이 같은 카드가 바뀌었다면 저장 시
 * {@code @Version} 낙관적 락이 잡는다(다른 카드의 변경은 계정 락이 직렬화한다).
 */
@ExtendWith(MockitoExtension.class)
class ChangeSubLimitServiceTest {

    @Mock CardOrgAuthorizer authorizer;
    @Mock LoadCardAccountPort loadCardAccountPort;
    @Mock LoadCardPort loadCardPort;
    @Mock SaveCardPort saveCardPort;
    @Mock PublishCardEventPort publishCardEventPort;

    ChangeSubLimitService service;

    @BeforeEach
    void setUp() {
        service = new ChangeSubLimitService(authorizer, loadCardAccountPort, loadCardPort,
                saveCardPort, publishCardEventPort);
    }

    /** 잠긴 ACTIVE 카드계정 — id 1, 조직 3001. */
    private CardAccount stubActiveAccount(BigDecimal masterLimit) {
        CardAccount account = CardAccount.builder()
                .id(1L)
                .organizationId(3001L)
                .sellerId("777")
                .status(CardAccountStatus.SCREENING)
                .masterLimit(BigDecimal.ZERO)
                .build();
        account.activate(masterLimit, new LimitSnapshot(masterLimit, BigDecimal.ZERO,
                new BigDecimal("0.7000"), ReputationGrade.B, "seller*0.7"));
        when(loadCardAccountPort.findByIdForUpdate(1L)).thenReturn(Optional.of(account));
        return account;
    }

    /** 카드계정 1 에 속한 발급 상태 카드. */
    private Card stubCard(Long cardId, Long holderUserId, BigDecimal subLimit) {
        Card card = Card.builder()
                .id(cardId)
                .cardAccountId(1L)
                .holderUserId(holderUserId)
                .maskedCardNo("****-****-****-1234")
                .subLimit(subLimit)
                .status(github.lms.lemuel.card.domain.CardStatus.ISSUED)
                .build();
        when(loadCardPort.findById(cardId)).thenReturn(Optional.of(card));
        when(saveCardPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return card;
    }

    @Test
    @DisplayName("서브한도 상향은 자기 몫을 뺀 합계와 비교한다 — 자기 자신을 두 번 세면 안 된다")
    void raiseComparesAgainstSumExcludingSelf() {
        // 마스터 100만, 기존 카드 A=60만(본인), B=30만 → 합계 90만
        // A 를 70만으로 올리면 70+30=100만 → 정확히 한도. 허용돼야 한다.
        stubActiveAccount(new BigDecimal("1000000"));
        stubCard(10L, 1L, new BigDecimal("600000"));
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(new BigDecimal("900000"));

        service.change(new ChangeSubLimitCommand(10L, new BigDecimal("700000"), 100L));

        verify(saveCardPort).save(argThat(c -> c.getSubLimit().compareTo(new BigDecimal("700000")) == 0));
    }

    /**
     * 위 테스트의 짝 — 1원만 더 올리면 거부돼야 "뺀 합계"가 실제로 검증에 쓰이는지 확인된다.
     * 검증을 통째로 생략해도 첫 테스트는 통과하기 때문이다.
     */
    @Test
    @DisplayName("자기 몫을 뺀 합계로도 초과하면 거부한다 — 검증을 건너뛴 게 아니다")
    void raiseBeyondRemainingIsRejected() {
        stubActiveAccount(new BigDecimal("1000000"));
        stubCardWithoutSaveStub(10L, 1L, new BigDecimal("600000"));
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(new BigDecimal("900000"));

        assertThatThrownBy(() -> service.change(
                new ChangeSubLimitCommand(10L, new BigDecimal("700001"), 100L)))
                .isInstanceOf(SubLimitExceededException.class);
        verify(saveCardPort, never()).save(any());
        verify(publishCardEventPort, never()).publishSubLimitChanged(any(), any(), any());
    }

    @Test
    @DisplayName("서브한도 하향은 항상 허용된다 — 합계가 이미 초과 상태여도 줄이는 방향은 막지 않는다")
    void lowerAlwaysAllowed() {
        stubActiveAccount(new BigDecimal("1000000"));
        stubCard(10L, 1L, new BigDecimal("600000"));
        // 클램프 이력 등으로 합계가 마스터를 이미 넘긴 상황을 가정한다.
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(new BigDecimal("1200000"));

        service.change(new ChangeSubLimitCommand(10L, new BigDecimal("300000"), 100L));

        verify(saveCardPort).save(argThat(c -> c.getSubLimit().compareTo(new BigDecimal("300000")) == 0));
    }

    @Test
    @DisplayName("CANCELED 카드의 한도는 바꿀 수 없다")
    void canceledCardLimitImmutable() {
        stubActiveAccount(new BigDecimal("1000000"));
        Card canceled = Card.builder()
                .id(10L).cardAccountId(1L).holderUserId(1L)
                .maskedCardNo("****-****-****-1234")
                .subLimit(new BigDecimal("100000"))
                .status(github.lms.lemuel.card.domain.CardStatus.ISSUED)
                .build();
        canceled.cancel();
        when(loadCardPort.findById(10L)).thenReturn(Optional.of(canceled));

        assertThatThrownBy(() -> service.change(
                new ChangeSubLimitCommand(10L, new BigDecimal("200000"), 100L)))
                .isInstanceOf(InvalidCardTransitionException.class);
        verify(saveCardPort, never()).save(any());
        // 바꿀 수 없는 카드 때문에 락 구간에서 집계 쿼리를 돌리지 않는다.
        verify(loadCardPort, never()).sumActiveSubLimits(any());
    }

    @Test
    @DisplayName("정지된 카드의 한도는 바꿀 수 있다 — 복직 전에 미리 조정하는 경로")
    void suspendedCardLimitIsMutable() {
        stubActiveAccount(new BigDecimal("1000000"));
        Card suspended = Card.builder()
                .id(10L).cardAccountId(1L).holderUserId(1L)
                .maskedCardNo("****-****-****-1234")
                .subLimit(new BigDecimal("600000"))
                .status(github.lms.lemuel.card.domain.CardStatus.ISSUED)
                .build();
        suspended.suspend();
        when(loadCardPort.findById(10L)).thenReturn(Optional.of(suspended));
        when(saveCardPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(new BigDecimal("600000"));

        service.change(new ChangeSubLimitCommand(10L, new BigDecimal("400000"), 100L));

        verify(saveCardPort).save(argThat(c -> c.getSubLimit().compareTo(new BigDecimal("400000")) == 0));
    }

    /**
     * 발급과 같은 이유로 순서가 곧 정확성이다 — 합계를 읽은 뒤에 잠그면 그 사이에 끼어든
     * 다른 카드의 변경이 합계에서 빠진다. 카드 조회가 락보다 앞서는 것은 소속 계정을
     * 알아내기 위한 불가피한 순서고, 같은 카드의 경합은 {@code @Version} 이 맡는다.
     */
    @Test
    @DisplayName("계정을 잠근 뒤에 합계를 재계산한다")
    void locksAccountBeforeReadingSum() {
        stubActiveAccount(new BigDecimal("1000000"));
        stubCard(10L, 1L, new BigDecimal("600000"));
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(new BigDecimal("600000"));

        service.change(new ChangeSubLimitCommand(10L, new BigDecimal("700000"), 100L));

        InOrder order = inOrder(loadCardAccountPort, loadCardPort, saveCardPort);
        order.verify(loadCardPort).findById(10L);
        order.verify(loadCardAccountPort).findByIdForUpdate(1L);
        order.verify(loadCardPort).sumActiveSubLimits(1L);
        order.verify(saveCardPort).save(any());
    }

    @Test
    @DisplayName("없는 카드는 404 — 계정을 잠그기도 전에 끝난다")
    void unknownCardIsNotFound() {
        when(loadCardPort.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.change(
                new ChangeSubLimitCommand(10L, new BigDecimal("200000"), 100L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARD_NOT_FOUND);
        verify(loadCardAccountPort, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("한도 변경은 OWNER 만 — MANAGER 도 막힌다")
    void onlyOwnerCanChangeLimit() {
        stubActiveAccount(new BigDecimal("1000000"));
        stubCardWithoutSaveStub(10L, 1L, new BigDecimal("600000"));
        doThrow(new BusinessException(ErrorCode.CARD_FORBIDDEN))
                .when(authorizer).requireRole(eq(3001L), eq(100L), eq(Set.of(OrgRole.OWNER)), anyString());

        assertThatThrownBy(() -> service.change(
                new ChangeSubLimitCommand(10L, new BigDecimal("700000"), 100L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARD_FORBIDDEN);
        verify(saveCardPort, never()).save(any());
    }

    @Test
    @DisplayName("변경분은 이전 한도와 함께 발행된다 — 감사가 '얼마에서 얼마로'를 알아야 한다")
    void publishesPreviousAndNewLimit() {
        CardAccount account = stubActiveAccount(new BigDecimal("1000000"));
        stubCard(10L, 1L, new BigDecimal("600000"));
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(new BigDecimal("600000"));

        Card changed = service.change(new ChangeSubLimitCommand(10L, new BigDecimal("700000"), 100L));

        assertThat(changed.getSubLimit()).isEqualByComparingTo("700000");
        verify(publishCardEventPort).publishSubLimitChanged(
                changed, account, new BigDecimal("600000"));
    }

    /** save 스텁이 필요 없는(저장까지 못 가는) 테스트용 — strict stubbing 을 지키기 위해 분리한다. */
    private void stubCardWithoutSaveStub(Long cardId, Long holderUserId, BigDecimal subLimit) {
        when(loadCardPort.findById(cardId)).thenReturn(Optional.of(Card.builder()
                .id(cardId)
                .cardAccountId(1L)
                .holderUserId(holderUserId)
                .maskedCardNo("****-****-****-1234")
                .subLimit(subLimit)
                .status(github.lms.lemuel.card.domain.CardStatus.ISSUED)
                .build()));
    }
}
