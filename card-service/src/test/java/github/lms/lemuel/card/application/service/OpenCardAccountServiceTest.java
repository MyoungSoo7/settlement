package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.OpenCardAccountUseCase.OpenCardAccountCommand;
import github.lms.lemuel.card.application.port.out.FundingUnavailableException;
import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.LoadOrgProjectionPort;
import github.lms.lemuel.card.application.port.out.LoadOrgProjectionPort.OrgView;
import github.lms.lemuel.card.application.port.out.LoadReputationPort;
import github.lms.lemuel.card.application.port.out.LoadSellerFundingPort;
import github.lms.lemuel.card.application.port.out.LoadSellerFundingPort.SellerFunding;
import github.lms.lemuel.card.application.port.out.PublishCardEventPort;
import github.lms.lemuel.card.application.port.out.SaveCardAccountPort;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import github.lms.lemuel.card.domain.CardLimitPolicy;
import github.lms.lemuel.card.domain.ReputationGrade;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 카드계정 개설(심사) 유스케이스 테스트.
 *
 * <p>이 테스트가 고정하는 것은 산식이 아니라 <b>순서</b>다 —
 * 인가 → 조직 검증 → 중복 검증 → 재원 조회 → 평판 조회 → 산정 → 저장 → 발행.
 * 재원 조회는 외부 호출이라 인가·검증을 통과한 뒤에만 일어나야 한다(권한 없는 요청이
 * account-service 를 두드리게 두면 그 자체가 증폭 경로다).
 */
@ExtendWith(MockitoExtension.class)
class OpenCardAccountServiceTest {

    @Mock CardOrgAuthorizer authorizer;
    @Mock LoadOrgProjectionPort loadOrgProjectionPort;
    @Mock LoadCardAccountPort loadCardAccountPort;
    @Mock SaveCardAccountPort saveCardAccountPort;
    @Mock LoadSellerFundingPort loadSellerFundingPort;
    @Mock LoadReputationPort loadReputationPort;
    @Mock PublishCardEventPort publishCardEventPort;

    OpenCardAccountService service;

    @BeforeEach
    void setUp() {
        // 정책은 실물을 쓴다 — 산식을 목으로 대체하면 "한도가 실제로 얼마인가"를 검증하지 못한다.
        service = new OpenCardAccountService(
                authorizer,
                loadOrgProjectionPort,
                loadCardAccountPort,
                saveCardAccountPort,
                loadSellerFundingPort,
                loadReputationPort,
                publishCardEventPort,
                new CardLimitPolicy(new BigDecimal("0.70"), new BigDecimal("300000")));
    }

    /** 저장 어댑터는 영속 id 가 채워진 인스턴스를 돌려준다 — 이벤트의 cardAccountId 가 여기서 나온다. */
    private void stubSaveEchoesBack() {
        when(saveCardAccountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubOrgAndFunding(BigDecimal sellerPayable, ReputationGrade grade) {
        when(loadOrgProjectionPort.findOrg(3001L))
                .thenReturn(Optional.of(new OrgView(3001L, "SELLER", "777")));
        when(loadCardAccountPort.findByOrganizationId(3001L)).thenReturn(Optional.empty());
        when(loadSellerFundingPort.load("777"))
                .thenReturn(new SellerFunding(sellerPayable, BigDecimal.ZERO));
        when(loadReputationPort.gradeOf("777")).thenReturn(grade);
    }

    @Test
    @DisplayName("심사 통과 → ACTIVE 저장 + account_opened 발행")
    void openApprovedPublishesEvent() {
        when(loadOrgProjectionPort.findOrg(3001L))
                .thenReturn(Optional.of(new OrgView(3001L, "SELLER", "777")));
        when(loadCardAccountPort.findByOrganizationId(3001L)).thenReturn(Optional.empty());
        when(loadSellerFundingPort.load("777"))
                .thenReturn(new SellerFunding(new BigDecimal("800000"), new BigDecimal("200000")));
        when(loadReputationPort.gradeOf("777")).thenReturn(ReputationGrade.B);
        stubSaveEchoesBack();

        CardAccount account = service.open(new OpenCardAccountCommand(3001L, 100L));

        assertThat(account.getStatus()).isEqualTo(CardAccountStatus.ACTIVE);
        assertThat(account.getMasterLimit()).isEqualByComparingTo("700000");
        // 근거 없는 한도를 남기지 않는다 — 승인에도 시산표가 붙는다.
        assertThat(account.getLimitSnapshot()).isNotNull();
        assertThat(account.getLimitSnapshot().reputationGrade()).isEqualTo(ReputationGrade.B);
        verify(publishCardEventPort).publishAccountOpened(any());
    }

    @Test
    @DisplayName("E등급은 422 로 거절되고 REJECTED 로 기록된다 — 근거를 남긴다")
    void openRejectedForGradeE() {
        stubOrgAndFunding(new BigDecimal("10000000"), ReputationGrade.E);

        assertThatThrownBy(() -> service.open(new OpenCardAccountCommand(3001L, 100L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARD_SCREENING_REJECTED);

        ArgumentCaptor<CardAccount> saved = ArgumentCaptor.forClass(CardAccount.class);
        verify(saveCardAccountPort).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(CardAccountStatus.REJECTED);
        assertThat(saved.getValue().getLimitSnapshot()).isNotNull();
        assertThat(saved.getValue().getRejectReason()).contains("평판 등급 E");
        verify(publishCardEventPort, never()).publishAccountOpened(any());
    }

    /**
     * 재원은 충분한데 산정액이 최소한도에 못 미치는 경로. E등급 탈락과 <b>사유가 달라야</b> 한다 —
     * 감사·CS 응대에서 "평판 때문"과 "규모 때문"은 다른 처방이 나온다.
     */
    @Test
    @DisplayName("최소한도 미달도 REJECTED 로 기록되며 사유가 평판 탈락과 구분된다")
    void openRejectedBelowMinimum() {
        stubOrgAndFunding(new BigDecimal("100000"), ReputationGrade.A);

        assertThatThrownBy(() -> service.open(new OpenCardAccountCommand(3001L, 100L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARD_SCREENING_REJECTED);

        ArgumentCaptor<CardAccount> saved = ArgumentCaptor.forClass(CardAccount.class);
        verify(saveCardAccountPort).save(saved.capture());
        assertThat(saved.getValue().getRejectReason()).contains("최소한도");
    }

    @Test
    @DisplayName("조직 타입이 SELLER 가 아니면 422 — 1단계는 셀러 전용")
    void nonSellerOrgRejected() {
        when(loadOrgProjectionPort.findOrg(3001L))
                .thenReturn(Optional.of(new OrgView(3001L, "CORPORATE", "005930")));

        assertThatThrownBy(() -> service.open(new OpenCardAccountCommand(3001L, 100L)))
                .isInstanceOf(BusinessException.class);
        // 셀러가 아닌 조직에 "심사 탈락" 기록을 남기지 않는다 — 심사를 한 적이 없다.
        verify(saveCardAccountPort, never()).save(any());
    }

    @Test
    @DisplayName("externalRef 가 없으면 sellerId 를 해석할 수 없어 422")
    void missingExternalRefRejected() {
        when(loadOrgProjectionPort.findOrg(3001L))
                .thenReturn(Optional.of(new OrgView(3001L, "SELLER", null)));

        assertThatThrownBy(() -> service.open(new OpenCardAccountCommand(3001L, 100L)))
                .isInstanceOf(BusinessException.class);
        verify(saveCardAccountPort, never()).save(any());
    }

    /**
     * 조직 프로젝션이 아직 안 왔을 때. 이벤트 소비 지연은 "심사 탈락"이 아니라 "지금은 판단 불가"다 —
     * REJECTED 를 남기면 사실이 아닌 기록이 되고, 터미널 상태라 재시도도 막힌다.
     */
    @Test
    @DisplayName("조직 프로젝션이 아직 없으면 아무것도 저장하지 않고 거절한다")
    void unknownOrganizationRejectedWithoutRecord() {
        when(loadOrgProjectionPort.findOrg(3001L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.open(new OpenCardAccountCommand(3001L, 100L)))
                .isInstanceOf(BusinessException.class);
        verify(saveCardAccountPort, never()).save(any());
        verify(loadSellerFundingPort, never()).load(anyString());
    }

    @Test
    @DisplayName("이미 카드계정이 있으면 409")
    void duplicateAccountRejected() {
        when(loadOrgProjectionPort.findOrg(3001L))
                .thenReturn(Optional.of(new OrgView(3001L, "SELLER", "777")));
        when(loadCardAccountPort.findByOrganizationId(3001L))
                .thenReturn(Optional.of(mock(CardAccount.class)));

        assertThatThrownBy(() -> service.open(new OpenCardAccountCommand(3001L, 100L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARD_ACCOUNT_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("OWNER 가 아니면 403 — 재원 조회조차 하지 않는다")
    void nonOwnerForbiddenBeforeFundingCall() {
        doThrow(new BusinessException(ErrorCode.CARD_FORBIDDEN))
                .when(authorizer).requireRole(eq(3001L), eq(200L), any(), anyString());

        assertThatThrownBy(() -> service.open(new OpenCardAccountCommand(3001L, 200L)))
                .isInstanceOf(BusinessException.class);
        verify(loadSellerFundingPort, never()).load(anyString());
        verify(loadOrgProjectionPort, never()).findOrg(any());
    }

    @Test
    @DisplayName("재원 조회 실패는 503 으로 번역되고 아무것도 저장하지 않는다")
    void fundingFailureIsTranslatedAndNothingSaved() {
        when(loadOrgProjectionPort.findOrg(3001L))
                .thenReturn(Optional.of(new OrgView(3001L, "SELLER", "777")));
        when(loadCardAccountPort.findByOrganizationId(3001L)).thenReturn(Optional.empty());
        when(loadSellerFundingPort.load("777")).thenThrow(new FundingUnavailableException("down", null));

        assertThatThrownBy(() -> service.open(new OpenCardAccountCommand(3001L, 100L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARD_FUNDING_UNAVAILABLE);
        verify(saveCardAccountPort, never()).save(any());
    }
}
