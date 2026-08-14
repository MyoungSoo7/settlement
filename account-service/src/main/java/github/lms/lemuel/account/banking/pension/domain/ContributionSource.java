package github.lms.lemuel.account.banking.pension.domain;

/**
 * 퇴직연금 부담금의 납입 주체.
 *
 * <p>제도별 허용 조합은 {@link PensionScheme} 이 소유한다 — DB 는 사용자(사업장) 부담금만, DC 는
 * 사용자 부담금에 가입자 추가납입을 더할 수 있고, IRP 는 가입자 본인 납입만 성립한다.
 * GL 은 이 구분을 기표하지 않는다(적립금 부채로만 집계) — banking 서브원장의 관심사다.
 */
public enum ContributionSource {

    /** 사용자(사업장) 부담금 — DB·DC 의 법정 부담금. */
    EMPLOYER,

    /** 가입자 본인 부담금 — DC 추가납입, IRP 개인 납입. */
    EMPLOYEE
}
