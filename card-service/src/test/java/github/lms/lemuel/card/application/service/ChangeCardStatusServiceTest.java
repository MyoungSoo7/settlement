package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.ChangeCardStatusUseCase.ChangeCardStatusCommand;
import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.LoadCardPort;
import github.lms.lemuel.card.application.port.out.PublishCardEventPort;
import github.lms.lemuel.card.application.port.out.SaveCardPort;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import github.lms.lemuel.card.domain.CardStatus;
import github.lms.lemuel.card.domain.LimitSnapshot;
import github.lms.lemuel.card.domain.OrgRole;
import github.lms.lemuel.card.domain.ReputationGrade;
import github.lms.lemuel.card.domain.exception.InvalidCardTransitionException;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 카드 상태 변경 유스케이스 테스트.
 *
 * <p>핵심은 <b>정지가 한도를 반납하지 않는다</b>는 것이다. 합계 기준이 {@code status <> CANCELED}
 * 라 정지 카드도 계속 자기 몫을 점유하고, 그래서 복직(resume) 시 다른 카드에 이미 배분돼 버린
 * 한도와 충돌하지 않는다. 반대로 해지(CANCELED)는 터미널이라 몫을 실제로 반납한다.
 *
 * <p>또 하나 — <b>상태가 실제로 바뀌지 않았으면 이벤트를 내지 않는다</b>. suspend() 는 멱등이라
 * 재수신된 이탈 이벤트(Task 12)가 같은 카드를 여러 번 정지시킬 수 있는데, 그때마다 발행하면
 * 소비자는 일어나지 않은 상태 변화를 계속 통지받는다.
 */
@ExtendWith(MockitoExtension.class)
class ChangeCardStatusServiceTest {

    @Mock CardOrgAuthorizer authorizer;
    @Mock LoadCardAccountPort loadCardAccountPort;
    @Mock LoadCardPort loadCardPort;
    @Mock SaveCardPort saveCardPort;
    @Mock PublishCardEventPort publishCardEventPort;

    ChangeCardStatusService statusService;

    @BeforeEach
    void setUp() {
        statusService = new ChangeCardStatusService(authorizer, loadCardAccountPort, loadCardPort,
                saveCardPort, publishCardEventPort);
    }

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

    private static Card card(BigDecimal subLimit) {
        return Card.builder()
                .id(10L)
                .cardAccountId(1L)
                .holderUserId(888L)
                .maskedCardNo("****-****-****-1234")
                .subLimit(subLimit)
                .status(CardStatus.ISSUED)
                .build();
    }

    private void stubSaveEcho() {
        when(saveCardPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("카드 정지는 서브한도 합계를 줄이지 않는다 — 재개 시 다른 카드와 충돌하지 않기 위해")
    void suspendingDoesNotFreeLimit() {
        stubActiveAccount(new BigDecimal("1000000"));
        Card card = card(new BigDecimal("600000"));
        when(loadCardPort.findById(10L)).thenReturn(Optional.of(card));
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(new BigDecimal("600000"));
        stubSaveEcho();

        statusService.change(new ChangeCardStatusCommand(10L, CardStatus.SUSPENDED, "휴직", 100L));

        // sumActiveSubLimits 는 status <> 'CANCELED' 기준이므로 정지 후에도 그대로다.
        assertThat(loadCardPort.sumActiveSubLimits(1L)).isEqualByComparingTo("600000");
        assertThat(card.getStatus()).isEqualTo(CardStatus.SUSPENDED);
    }

    @Test
    @DisplayName("이미 정지된 카드를 다시 정지하면 이벤트를 내지 않는다 — 재수신 멱등")
    void repeatedSuspendPublishesNothing() {
        stubActiveAccount(new BigDecimal("1000000"));
        Card card = card(new BigDecimal("600000"));
        card.suspend();
        when(loadCardPort.findById(10L)).thenReturn(Optional.of(card));

        statusService.change(new ChangeCardStatusCommand(10L, CardStatus.SUSPENDED, "휴직", 100L));

        verify(publishCardEventPort, never()).publishStatusChanged(any(), any(), any(), anyString());
        verify(saveCardPort, never()).save(any());
    }

    @Test
    @DisplayName("정지 → 재개는 이전 상태와 사유를 실어 발행한다")
    void resumePublishesPreviousStatus() {
        CardAccount account = stubActiveAccount(new BigDecimal("1000000"));
        Card card = card(new BigDecimal("600000"));
        card.suspend();
        when(loadCardPort.findById(10L)).thenReturn(Optional.of(card));
        stubSaveEcho();

        Card resumed = statusService.change(
                new ChangeCardStatusCommand(10L, CardStatus.ISSUED, "복직", 100L));

        assertThat(resumed.getStatus()).isEqualTo(CardStatus.ISSUED);
        verify(publishCardEventPort).publishStatusChanged(resumed, account, CardStatus.SUSPENDED, "복직");
    }

    @Test
    @DisplayName("해지는 멱등이 아니다 — CANCELED 카드를 다시 해지하면 전이 오류")
    void cancelIsNotIdempotent() {
        stubActiveAccount(new BigDecimal("1000000"));
        Card card = card(new BigDecimal("600000"));
        card.cancel();
        when(loadCardPort.findById(10L)).thenReturn(Optional.of(card));

        assertThatThrownBy(() -> statusService.change(
                new ChangeCardStatusCommand(10L, CardStatus.CANCELED, "중복 해지", 100L)))
                .isInstanceOf(InvalidCardTransitionException.class);
        verify(saveCardPort, never()).save(any());
    }

    @Test
    @DisplayName("상태 변경은 OWNER·MANAGER 둘 다 가능하다 — 한도 배분과 달리 운영 행위다")
    void ownerAndManagerMayChangeStatus() {
        stubActiveAccount(new BigDecimal("1000000"));
        when(loadCardPort.findById(10L)).thenReturn(Optional.of(card(new BigDecimal("600000"))));
        stubSaveEcho();

        statusService.change(new ChangeCardStatusCommand(10L, CardStatus.SUSPENDED, "휴직", 100L));

        verify(authorizer).requireRole(eq(3001L), eq(100L),
                eq(Set.of(OrgRole.OWNER, OrgRole.MANAGER)), anyString());
    }

    @Test
    @DisplayName("권한이 없으면 상태를 건드리지 못한다")
    void forbiddenRequesterCannotChangeStatus() {
        stubActiveAccount(new BigDecimal("1000000"));
        Card card = card(new BigDecimal("600000"));
        when(loadCardPort.findById(10L)).thenReturn(Optional.of(card));
        doThrow(new BusinessException(ErrorCode.CARD_FORBIDDEN))
                .when(authorizer).requireRole(any(), any(), any(), anyString());

        assertThatThrownBy(() -> statusService.change(
                new ChangeCardStatusCommand(10L, CardStatus.CANCELED, "임의 해지", 100L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARD_FORBIDDEN);
        assertThat(card.getStatus()).isEqualTo(CardStatus.ISSUED);
        verify(saveCardPort, never()).save(any());
    }

    @Test
    @DisplayName("없는 카드는 404")
    void unknownCardIsNotFound() {
        when(loadCardPort.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> statusService.change(
                new ChangeCardStatusCommand(10L, CardStatus.SUSPENDED, "휴직", 100L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARD_NOT_FOUND);
        verify(loadCardAccountPort, never()).findByIdForUpdate(any());
    }

    /**
     * 사유 없는 상태 변경은 받지 않는다 — 카드 정지·해지는 감사 대상이라 "누가 왜"가 남지 않으면
     * 사후에 재현할 수 없다. 도메인이 아니라 유스케이스 입력 계약이므로 여기서 막는다.
     */
    @Test
    @DisplayName("사유가 비면 거부한다 — 근거 없는 상태 변경을 남기지 않는다")
    void blankReasonRejected() {
        assertThatThrownBy(() -> statusService.change(
                new ChangeCardStatusCommand(10L, CardStatus.SUSPENDED, "  ", 100L)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(loadCardPort, never()).findById(any());
    }
}
