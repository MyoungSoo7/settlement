package github.lms.lemuel.account.banking.pension.domain;

/**
 * 퇴직연금 중도인출 <b>법정 사유</b> — 근로자퇴직급여보장법 시행령이 정한 여섯 가지가 전부다.
 *
 * <p>사유를 자유 문자열로 받으면 "임의 사유 인출"이 표현 가능해진다. enum 으로 닫아두면
 * 법정 사유가 아닌 인출은 <b>타입 수준에서 표현 불가능</b>해진다 — 이것이 이 열거형의 존재 이유다.
 * (제도 자체가 중도인출을 허용하는지는 별개 판정이다 — {@link PensionScheme#permitsMidWithdrawal()},
 * DB형은 언제나 불가.)
 */
public enum MidWithdrawalReason {

    /** 무주택자인 가입자가 본인 명의로 주택을 구입하는 경우. */
    HOMELESS_HOUSE_PURCHASE,

    /** 가입자·배우자·부양가족이 6개월 이상 요양을 필요로 하는 경우. */
    LONG_TERM_CARE_6_MONTHS,

    /** 신청일로부터 역산해 5년 이내에 파산선고를 받은 경우. */
    BANKRUPTCY,

    /** 신청일로부터 역산해 5년 이내에 개인회생절차 개시 결정을 받은 경우. */
    PERSONAL_REHABILITATION,

    /** 천재지변 등으로 피해를 입은 경우. */
    NATURAL_DISASTER,

    /** 그 밖에 고용노동부장관이 정하여 고시하는 사유. */
    MINISTER_NOTICE
}
