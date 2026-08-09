package github.lms.lemuel.account.banking.pension.adapter.in.web;

import github.lms.lemuel.account.banking.pension.adapter.in.web.dto.ChangeInvestmentInstructionRequest;
import github.lms.lemuel.account.banking.pension.adapter.in.web.dto.ContributeRequest;
import github.lms.lemuel.account.banking.pension.adapter.in.web.dto.MidWithdrawalRequest;
import github.lms.lemuel.account.banking.pension.adapter.in.web.dto.OpenPensionRequest;
import github.lms.lemuel.account.banking.pension.adapter.in.web.dto.PayBenefitRequest;
import github.lms.lemuel.account.banking.pension.adapter.in.web.dto.RetirementPensionResponse;
import github.lms.lemuel.account.banking.pension.adapter.in.web.dto.StartBenefitRequest;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionQuery;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.ChangeInvestmentInstructionCommand;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.ContributeCommand;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.OpenPensionCommand;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.PayBenefitCommand;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.SettleInterestCommand;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.StartBenefitCommand;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.WithdrawMidwayCommand;
import github.lms.lemuel.account.banking.pension.domain.BenefitType;
import github.lms.lemuel.account.banking.pension.domain.ContributionSource;
import github.lms.lemuel.account.banking.pension.domain.MidWithdrawalReason;
import github.lms.lemuel.account.banking.pension.domain.PensionScheme;
import github.lms.lemuel.account.banking.pension.domain.PensionStatus;
import github.lms.lemuel.account.banking.pension.domain.PensionTransaction;
import github.lms.lemuel.account.banking.pension.domain.PensionTransactionType;
import github.lms.lemuel.account.banking.pension.domain.PrincipalGuaranteedProduct;
import github.lms.lemuel.account.banking.pension.domain.RetirementPension;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 퇴직연금 컨트롤러 단위 테스트 (MockMvc 없이 순수 Mockito).
 *
 * <p>여기서 지키는 계약은 둘이다 — (1) <b>가입자 식별자는 오직 JWT 주체에서만</b> 나온다,
 * (2) 요청 DTO 의 값이 커맨드의 올바른 자리로 옮겨진다. 커맨드를 캡처해 그 두 가지를 함께 본다.
 */
@ExtendWith(MockitoExtension.class)
class RetirementPensionControllerTest {

    private static final LocalDate OPENED = LocalDate.of(2020, 1, 1);
    private static final LocalDate BIRTH = LocalDate.of(1969, 3, 2);
    private static final BigDecimal RATE = new BigDecimal("0.035");

    @Mock RetirementPensionUseCase retirementPensionUseCase;
    @Mock RetirementPensionQuery retirementPensionQuery;

    private RetirementPensionController controller;

    /** userId 77 인 정상 토큰 — 요청 어디에도 가입자 식별자가 실리지 않는다는 점이 핵심이다. */
    private static Authentication authenticated() {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(77L, "user@lemuel.co.kr", "ROLE_USER"), "n/a", List.of());
    }

    @BeforeEach
    void setUp() {
        controller = new RetirementPensionController(retirementPensionUseCase, retirementPensionQuery);
    }

    // ---------------------------------------------------------------- 정상 경로

    @Test
    void 가입은_201과_함께_생성된_계약을_반환한다() {
        when(retirementPensionUseCase.open(any())).thenReturn(pension());

        ResponseEntity<RetirementPensionResponse> response = controller.open(
                new OpenPensionRequest(PensionScheme.DC, "레무엘테크", BIRTH, RATE, "정기예금형", RATE),
                authenticated());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().subscriberId()).isEqualTo("77");

        ArgumentCaptor<OpenPensionCommand> captor = ArgumentCaptor.forClass(OpenPensionCommand.class);
        verify(retirementPensionUseCase).open(captor.capture());
        assertThat(captor.getValue().subscriberId()).isEqualTo("77");
        assertThat(captor.getValue().scheme()).isEqualTo(PensionScheme.DC);
        assertThat(captor.getValue().employerName()).isEqualTo("레무엘테크");
        // 개설일은 커맨드에 없다 — 서버 Clock 이 정한다. 가입 때 한 번 받는 것은 생년월일뿐이다.
        assertThat(captor.getValue().birthDate()).isEqualTo(BIRTH);
    }

    @Test
    void 부담금_납입_요청은_토큰의_가입자로_커맨드를_만든다() {
        when(retirementPensionUseCase.contribute(any())).thenReturn(pension());

        ResponseEntity<RetirementPensionResponse> response = controller.contribute(
                5L, new ContributeRequest(new BigDecimal("1000000"), ContributionSource.EMPLOYER),
                authenticated());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        ArgumentCaptor<ContributeCommand> captor = ArgumentCaptor.forClass(ContributeCommand.class);
        verify(retirementPensionUseCase).contribute(captor.capture());
        assertThat(captor.getValue().subscriberId()).isEqualTo("77");
        assertThat(captor.getValue().pensionId()).isEqualTo(5L);
        assertThat(captor.getValue().amount()).isEqualByComparingTo("1000000");
        assertThat(captor.getValue().source()).isEqualTo(ContributionSource.EMPLOYER);
    }

    /**
     * 이자 확정은 <b>본문이 없다</b> — 커맨드가 나르는 것은 계약 식별자뿐이다. 금액을 받으면 호출자가
     * 자기 적립금을 임의로 부풀리고 그것이 그대로 기표되므로, 금액도 발생일도 서버가 정한다.
     */
    @Test
    void 운용수익_확정은_계약_식별자만_커맨드로_옮긴다() {
        when(retirementPensionUseCase.settleInterest(any())).thenReturn(pension());

        ResponseEntity<RetirementPensionResponse> response = controller.settleInterest(5L, authenticated());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        ArgumentCaptor<SettleInterestCommand> captor = ArgumentCaptor.forClass(SettleInterestCommand.class);
        verify(retirementPensionUseCase).settleInterest(captor.capture());
        assertThat(captor.getValue().pensionId()).isEqualTo(5L);
    }

    /**
     * 운영자 경로의 다층 방어 — 라우트 권한(ADMIN/MANAGER)이 언젠가 느슨해지더라도 익명 호출이
     * 남의 적립금에 이자를 붙일 수는 없어야 한다.
     */
    @Test
    void 인증_주체가_없으면_이자_확정은_403이다() {
        assertThatThrownBy(() -> controller.settleInterest(5L, null))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(retirementPensionUseCase);
    }

    @Test
    void 운용지시_변경_요청을_커맨드로_옮긴다() {
        when(retirementPensionUseCase.changeInvestmentInstruction(any())).thenReturn(pension());

        controller.changeInvestmentInstruction(
                5L, new ChangeInvestmentInstructionRequest("원리금보장 국공채형", new BigDecimal("0.028")),
                authenticated());

        ArgumentCaptor<ChangeInvestmentInstructionCommand> captor =
                ArgumentCaptor.forClass(ChangeInvestmentInstructionCommand.class);
        verify(retirementPensionUseCase).changeInvestmentInstruction(captor.capture());
        assertThat(captor.getValue().productName()).isEqualTo("원리금보장 국공채형");
        assertThat(captor.getValue().rate()).isEqualByComparingTo("0.028");
    }

    /**
     * 수급 개시 요청이 나르는 것은 급여 형태뿐이다 — 만 나이·가입기간을 받으면 자기신고로 연령
     * 요건을 통과할 수 있으므로, 둘 다 서버가 생년월일·개설일에서 파생한다.
     */
    @Test
    void 수급_개시_요청은_급여_형태만_커맨드로_옮긴다() {
        when(retirementPensionUseCase.startBenefit(any())).thenReturn(pension());

        controller.startBenefit(5L, new StartBenefitRequest(BenefitType.ANNUITY), authenticated());

        ArgumentCaptor<StartBenefitCommand> captor = ArgumentCaptor.forClass(StartBenefitCommand.class);
        verify(retirementPensionUseCase).startBenefit(captor.capture());
        assertThat(captor.getValue().benefitType()).isEqualTo(BenefitType.ANNUITY);
        assertThat(captor.getValue().subscriberId()).isEqualTo("77");
        assertThat(captor.getValue().pensionId()).isEqualTo(5L);
    }

    @Test
    void 급여_지급_요청을_커맨드로_옮긴다() {
        when(retirementPensionUseCase.payBenefit(any())).thenReturn(pension());

        controller.payBenefit(5L, new PayBenefitRequest(new BigDecimal("400000")), authenticated());

        ArgumentCaptor<PayBenefitCommand> captor = ArgumentCaptor.forClass(PayBenefitCommand.class);
        verify(retirementPensionUseCase).payBenefit(captor.capture());
        assertThat(captor.getValue().amount()).isEqualByComparingTo("400000");
        assertThat(captor.getValue().pensionId()).isEqualTo(5L);
    }

    @Test
    void 중도인출_요청은_법정사유를_그대로_넘긴다() {
        when(retirementPensionUseCase.withdrawMidway(any())).thenReturn(pension());

        controller.withdrawMidway(
                5L, new MidWithdrawalRequest(new BigDecimal("300000"),
                        MidWithdrawalReason.LONG_TERM_CARE_6_MONTHS),
                authenticated());

        ArgumentCaptor<WithdrawMidwayCommand> captor = ArgumentCaptor.forClass(WithdrawMidwayCommand.class);
        verify(retirementPensionUseCase).withdrawMidway(captor.capture());
        assertThat(captor.getValue().reason()).isEqualTo(MidWithdrawalReason.LONG_TERM_CARE_6_MONTHS);
        assertThat(captor.getValue().amount()).isEqualByComparingTo("300000");
    }

    @Test
    void 단건_조회는_경로_식별자와_토큰_가입자를_함께_넘긴다() {
        when(retirementPensionQuery.get("77", 5L)).thenReturn(pension());

        ResponseEntity<RetirementPensionResponse> response = controller.get(5L, authenticated());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(5L);
    }

    @Test
    void 목록_조회_경로에는_가입자_식별자가_없다() {
        when(retirementPensionQuery.listMine("77")).thenReturn(List.of(pension()));

        ResponseEntity<List<RetirementPensionResponse>> response = controller.listMine(authenticated());

        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).subscriberId()).isEqualTo("77");
    }

    // ---------------------------------------------------------------- 응답 매핑

    @Test
    void 응답은_거래이력과_다음_seq를_함께_노출한다() {
        when(retirementPensionQuery.get("77", 5L)).thenReturn(pension());

        RetirementPensionResponse body = controller.get(5L, authenticated()).getBody();

        assertThat(body).isNotNull();
        assertThat(body.scheme()).isEqualTo(PensionScheme.DC);
        assertThat(body.employerName()).isEqualTo("레무엘테크");
        assertThat(body.status()).isEqualTo(PensionStatus.ACCUMULATING);
        assertThat(body.productName()).isEqualTo("정기예금형");
        assertThat(body.productRate()).isEqualByComparingTo("0.035");
        assertThat(body.annualRate()).isEqualByComparingTo("0.035");
        assertThat(body.accumulatedAmount()).isEqualByComparingTo("1000000");
        assertThat(body.openedOn()).isEqualTo(OPENED);
        assertThat(body.benefitStartedOn()).isNull();
        assertThat(body.benefitType()).isNull();
        assertThat(body.nextSeq()).isEqualTo(2L);
        assertThat(body.transactions()).singleElement().satisfies(view -> {
            assertThat(view.seq()).isEqualTo(1L);
            assertThat(view.type()).isEqualTo(PensionTransactionType.CONTRIBUTION);
            assertThat(view.amount()).isEqualByComparingTo("1000000");
            assertThat(view.contributionSource()).isEqualTo(ContributionSource.EMPLOYER);
            assertThat(view.midWithdrawalReason()).isNull();
            assertThat(view.occurredOn()).isEqualTo(OPENED);
        });
    }

    // ---------------------------------------------------------------- 인증 실패

    @Test
    void 인증이_없으면_403이다() {
        assertThatThrownBy(() -> controller.listMine(null))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(retirementPensionQuery, retirementPensionUseCase);
    }

    @Test
    void 주체가_AuthPrincipal이_아니면_403이다() {
        Authentication anonymous =
                new UsernamePasswordAuthenticationToken("anonymousUser", "n/a", List.of());

        assertThatThrownBy(() -> controller.get(5L, anonymous))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(retirementPensionQuery, retirementPensionUseCase);
    }

    @Test
    void userId가_없는_구토큰이면_403이다() {
        Authentication legacy = new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(null, "user@lemuel.co.kr", "ROLE_USER"), "n/a", List.of());

        assertThatThrownBy(() -> controller.contribute(
                5L, new ContributeRequest(new BigDecimal("1000"), ContributionSource.EMPLOYER), legacy))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(retirementPensionUseCase);
    }

    // ---------------------------------------------------------------- 픽스처

    private static RetirementPension pension() {
        return RetirementPension.reconstitute(5L, "77", PensionScheme.DC, "레무엘테크", BIRTH, RATE,
                new PrincipalGuaranteedProduct("정기예금형", RATE), PensionStatus.ACCUMULATING,
                OPENED, null, null, null, new BigDecimal("1000000"), 2L,
                List.of(PensionTransaction.reconstitute(1L, 1L, PensionTransactionType.CONTRIBUTION,
                        new BigDecimal("1000000"), ContributionSource.EMPLOYER, null, OPENED)));
    }
}
