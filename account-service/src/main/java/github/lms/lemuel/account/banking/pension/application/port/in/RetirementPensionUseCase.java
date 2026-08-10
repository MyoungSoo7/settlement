package github.lms.lemuel.account.banking.pension.application.port.in;

import github.lms.lemuel.account.banking.pension.domain.BenefitType;
import github.lms.lemuel.account.banking.pension.domain.ContributionSource;
import github.lms.lemuel.account.banking.pension.domain.MidWithdrawalReason;
import github.lms.lemuel.account.banking.pension.domain.PensionScheme;
import github.lms.lemuel.account.banking.pension.domain.RetirementPension;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 퇴직연금 계약 명령 인바운드 포트.
 *
 * <p><b>모든 커맨드의 첫 필드가 {@code subscriberId} 인 것은 의도다</b> — 이 값은 요청 본문·경로가
 * 아니라 JWT 주체에서만 채워지며(어댑터 책임), 서비스는 이 값으로 계약 소유권을 판정한다.
 * 커맨드에 가입자 식별자를 넣지 않으면 서비스가 소유권을 볼 수 없고, 요청에서 받으면 IDOR 이 된다.
 *
 * <p><b>커맨드에 날짜 필드가 없는 것도 의도다</b> — 발생일은 서비스가 주입받은 {@code Clock} 에서
 * 결정한다. 클라이언트가 날짜를 정할 수 있으면 소급 일자로 이자 경과일수를 부풀리거나 상태 전이
 * 순서를 뒤집을 수 있다(정기예금·적금과 동일 규약). 같은 이유로 이자 금액·만 나이·가입기간도
 * 커맨드에 없다 — 전부 서버가 산출한다.
 */
public interface RetirementPensionUseCase {

    /** 신규 가입 (DB·DC 는 사업장명 필수, IRP 는 금지). */
    RetirementPension open(OpenPensionCommand command);

    /** 부담금 납입 → GL {@code pensionContributionPaid}. */
    RetirementPension contribute(ContributeCommand command);

    /**
     * 운용수익(원리금보장 이자) 확정 → GL {@code pensionInterestSettled}.
     * <b>운영자 경로</b>(ADMIN/MANAGER) — 가입자가 스스로 이자를 만들 수 없어야 한다.
     */
    RetirementPension settleInterest(SettleInterestCommand command);

    /** 운용지시(원리금보장 상품) 변경 — GL 무전표(자금 이동이 없다). */
    RetirementPension changeInvestmentInstruction(ChangeInvestmentInstructionCommand command);

    /** 수급 개시 — 상태 전이만, GL 무전표. */
    RetirementPension startBenefit(StartBenefitCommand command);

    /**
     * 퇴직급여 지급 → GL {@code pensionBenefitPaid}.
     * <b>운영자 경로</b>(ADMIN/MANAGER) — 지급 대상은 언제나 계약에 적힌 가입자다.
     */
    RetirementPension payBenefit(PayBenefitCommand command);

    /** 법정 사유 중도인출 → GL {@code pensionMidWithdrawn}. */
    RetirementPension withdrawMidway(WithdrawMidwayCommand command);

    /**
     * @param employerName DB·DC 필수, IRP 는 반드시 null
     * @param birthDate    생년월일 — 수급 개시 연령 판정의 유일한 근거이므로 가입 때 한 번만 받는다.
     *                     수급 신청 때 받으면 요건 검사가 무력화된다(자기신고 한계는 애그리게이트 문서 참고)
     * @param annualRate   계약 기준 운용이율 — {@code [0,1)} 소수
     */
    record OpenPensionCommand(String subscriberId, PensionScheme scheme, String employerName,
                              LocalDate birthDate, BigDecimal annualRate,
                              String productName, BigDecimal productRate) { }

    record ContributeCommand(String subscriberId, Long pensionId, BigDecimal amount,
                             ContributionSource source) { }

    /**
     * 이자 금액이 없는 것이 핵심이다 — 서버가 계약이율·경과일수로 산출한다.
     *
     * <p>가입자 식별자도 없다: 이 경로는 ADMIN/MANAGER 전용(SecurityConfig)이라 호출자와 계약
     * 소유자가 다르다. GL 전표의 owner 는 호출자가 아니라 <b>계약에 적힌 가입자</b>에서 나온다.
     */
    record SettleInterestCommand(Long pensionId) { }

    record ChangeInvestmentInstructionCommand(String subscriberId, Long pensionId,
                                              String productName, BigDecimal rate) { }

    /** 만 나이·가입기간이 없는 것이 핵심이다 — 서버가 birthDate·openedOn 에서 파생한다. */
    record StartBenefitCommand(String subscriberId, Long pensionId, BenefitType benefitType) { }

    /**
     * 수급자 식별자가 없는 것이 핵심이다 — 지급 대상은 언제나 계약에 적힌 가입자다.
     * 이 경로도 ADMIN/MANAGER 전용이므로 호출자 식별자로 owner 를 정하면 남의 급여가 운영자
     * 계정으로 기표된다.
     */
    record PayBenefitCommand(Long pensionId, BigDecimal amount) { }

    record WithdrawMidwayCommand(String subscriberId, Long pensionId, BigDecimal amount,
                                 MidWithdrawalReason reason) { }
}
