package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.IssueCardUseCase.IssueCardCommand;
import github.lms.lemuel.card.application.port.out.CardIssuerPort;
import github.lms.lemuel.card.application.port.out.CardIssuerPort.IssuedCard;
import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.LoadCardPort;
import github.lms.lemuel.card.application.port.out.LoadOrgProjectionPort;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 임직원 카드 발급 유스케이스 테스트.
 *
 * <p>이 테스트가 고정하는 것은 <b>순서</b>다 — 락 → 인가 → 멤버십 → 중복 → 합계 재계산 → 채번 →
 * 저장 → 발행. 특히 "합계를 읽기 전에 잠근다"와 "검증을 통과하기 전에는 채번하지 않는다"
 * 두 가지가 무너지면 각각 한도 초과 발급과 회수 불가능한 유령 카드번호로 이어진다.
 *
 * <p>불변식 자체(master_limit ≥ Σ sub_limit)가 <b>동시 요청에서도</b> 지켜지는지는 목으로 증명할 수
 * 없다 — {@code CardIssuanceLimitConcurrencyIT} 가 실 PostgreSQL 로 그 부분을 맡는다.
 */
@ExtendWith(MockitoExtension.class)
class IssueCardServiceTest {

    @Mock CardOrgAuthorizer authorizer;
    @Mock LoadCardAccountPort loadCardAccountPort;
    @Mock LoadOrgProjectionPort loadOrgProjectionPort;
    @Mock LoadCardPort loadCardPort;
    @Mock SaveCardPort saveCardPort;
    @Mock CardIssuerPort cardIssuerPort;
    @Mock PublishCardEventPort publishCardEventPort;

    IssueCardService service;

    @BeforeEach
    void setUp() {
        service = new IssueCardService(authorizer, loadCardAccountPort, loadOrgProjectionPort,
                loadCardPort, saveCardPort, cardIssuerPort, publishCardEventPort);
    }

    private static final String MASKED = "****-****-****-1234";

    /** 잠긴 ACTIVE 카드계정 — id 1, 조직 3001. */
    private CardAccount stubActiveAccount(BigDecimal masterLimit) {
        CardAccount account = CardAccount.builder()
                .id(1L)
                .organizationId(3001L)
                .sellerId("777")
                .status(CardAccountStatus.SCREENING)
                .masterLimit(BigDecimal.ZERO)
                .build();
        account.activate(masterLimit, new LimitSnapshot(
                masterLimit, BigDecimal.ZERO, new BigDecimal("0.70"), ReputationGrade.B, "formula"));
        when(loadCardAccountPort.findByIdForUpdate(1L)).thenReturn(Optional.of(account));
        return account;
    }

    /** 채번·저장이 실제로 도달하는 경로에서만 스텁한다(strict stubbing 유지). */
    private void stubIssuerAndSave() {
        when(cardIssuerPort.issue(eq(1L), any())).thenReturn(new IssuedCard(MASKED));
        when(saveCardPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("발급은 카드계정을 비관적 락으로 잠근 뒤 합계를 재계산한다")
    void issueLocksAccountBeforeSumming() {
        stubActiveAccount(new BigDecimal("1000000"));
        stubIssuerAndSave();
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(new BigDecimal("900000"));
        when(loadOrgProjectionPort.findMemberRole(3001L, 888L)).thenReturn(Optional.of(OrgRole.STAFF));

        service.issue(new IssueCardCommand(1L, 888L, new BigDecimal("100000"), 100L));

        InOrder order = inOrder(loadCardAccountPort, loadCardPort, saveCardPort);
        order.verify(loadCardAccountPort).findByIdForUpdate(1L);
        order.verify(loadCardPort).sumActiveSubLimits(1L);
        order.verify(saveCardPort).save(any());
    }

    @Test
    @DisplayName("카드계정이 없으면 404 — 인가 판정도 하지 않는다")
    void unknownAccountIsNotFound() {
        when(loadCardAccountPort.findByIdForUpdate(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(
                new IssueCardCommand(9L, 888L, new BigDecimal("10000"), 100L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARD_ACCOUNT_NOT_FOUND);

        // 조직을 모르는 상태라 인가 자체가 성립하지 않는다 — 계정 존재 여부를 권한 없는
        // 호출자에게 흘리지 않으려면 여기서 404 로 끝나야 한다.
        verify(authorizer, never()).requireRole(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("대상이 조직의 활성 멤버가 아니면 422 — 채번도 하지 않는다")
    void nonMemberRejectedBeforeIssuing() {
        stubActiveAccount(new BigDecimal("1000000"));
        when(loadOrgProjectionPort.findMemberRole(3001L, 999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(
                new IssueCardCommand(1L, 999L, new BigDecimal("10000"), 100L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARD_HOLDER_NOT_MEMBER);
        verify(cardIssuerPort, never()).issue(any(), any());
    }

    @Test
    @DisplayName("이미 활성 카드가 있으면 409")
    void duplicateActiveCardRejected() {
        stubActiveAccount(new BigDecimal("1000000"));
        when(loadOrgProjectionPort.findMemberRole(3001L, 888L)).thenReturn(Optional.of(OrgRole.STAFF));
        when(loadCardPort.findActiveByHolder(1L, 888L)).thenReturn(Optional.of(mock(Card.class)));

        assertThatThrownBy(() -> service.issue(
                new IssueCardCommand(1L, 888L, new BigDecimal("10000"), 100L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARD_ALREADY_ISSUED);
        verify(cardIssuerPort, never()).issue(any(), any());
    }

    @Test
    @DisplayName("마스터 한도를 넘으면 422 이고 카드는 저장되지 않는다")
    void overLimitRejected() {
        stubActiveAccount(new BigDecimal("1000000"));
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(new BigDecimal("950000"));
        when(loadOrgProjectionPort.findMemberRole(3001L, 888L)).thenReturn(Optional.of(OrgRole.STAFF));

        assertThatThrownBy(() -> service.issue(
                new IssueCardCommand(1L, 888L, new BigDecimal("100000"), 100L)))
                .isInstanceOf(SubLimitExceededException.class);
        verify(saveCardPort, never()).save(any());
        // 채번은 한도 검증 뒤 — 거절된 요청이 발급사에 실물 번호를 태우면 회수할 수 없다.
        verify(cardIssuerPort, never()).issue(any(), any());
    }

    /**
     * SUSPENDED 카드계정에서의 발급은 {@code assertCanIssue} 가 막는다. 유스케이스가 이를
     * 삼키지 않고 그대로 올려야 {@code CardExceptionHandler} 가 INVALID_STATE 로 번역한다.
     */
    @Test
    @DisplayName("ACTIVE 가 아닌 카드계정에서는 발급되지 않는다")
    void nonActiveAccountCannotIssue() {
        CardAccount account = stubActiveAccount(new BigDecimal("1000000"));
        account.suspend();
        when(loadOrgProjectionPort.findMemberRole(3001L, 888L)).thenReturn(Optional.of(OrgRole.STAFF));
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.issue(
                new IssueCardCommand(1L, 888L, new BigDecimal("10000"), 100L)))
                .isInstanceOf(InvalidCardTransitionException.class);
        verify(cardIssuerPort, never()).issue(any(), any());
    }

    /**
     * 계획서 원안은 계정 스텁 없이 authorizer 만 던지게 했는데, 그러면 {@code findByIdForUpdate} 가
     * 기본값 {@code Optional.empty()} 를 돌려주어 <b>404 로 통과해버린다</b>(BusinessException 이긴
     * 하므로 어서션이 속는다). 계정을 실제로 잠근 뒤 403 이 나는지까지 확인한다.
     */
    @Test
    @DisplayName("OWNER 만 발급할 수 있다 — MANAGER 도 403")
    void onlyOwnerCanIssue() {
        stubActiveAccount(new BigDecimal("1000000"));
        doThrow(new BusinessException(ErrorCode.CARD_FORBIDDEN))
                .when(authorizer).requireRole(eq(3001L), eq(200L), eq(Set.of(OrgRole.OWNER)), anyString());

        assertThatThrownBy(() -> service.issue(
                new IssueCardCommand(1L, 888L, new BigDecimal("10000"), 200L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARD_FORBIDDEN);
        verify(cardIssuerPort, never()).issue(any(), any());
        verify(saveCardPort, never()).save(any());
    }

    @Test
    @DisplayName("발급 성공은 저장된 카드와 잠근 카드계정을 함께 이벤트로 싣는다")
    void publishesIssuedEventWithPersistedCard() {
        CardAccount account = stubActiveAccount(new BigDecimal("1000000"));
        stubIssuerAndSave();
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(BigDecimal.ZERO);
        when(loadOrgProjectionPort.findMemberRole(3001L, 888L)).thenReturn(Optional.of(OrgRole.STAFF));

        Card issued = service.issue(new IssueCardCommand(1L, 888L, new BigDecimal("100000"), 100L));

        assertThat(issued.getHolderUserId()).isEqualTo(888L);
        assertThat(issued.getMaskedCardNo()).isEqualTo(MASKED);
        assertThat(issued.getSubLimit()).isEqualByComparingTo("100000");
        // 조직 식별자는 카드가 아니라 카드계정에만 있다 — 소비자가 조직 단위로 반응하려면 둘 다 필요하다.
        verify(publishCardEventPort).publishIssued(issued, account);
    }
}
