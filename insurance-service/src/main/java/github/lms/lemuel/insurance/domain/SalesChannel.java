package github.lms.lemuel.insurance.domain;

/**
 * 판매채널 — V6 방카슈랑스 도입.
 *
 * <p>채널이 수수료 수령 주체(recipient_type)를 결정한다:
 * FC 채널은 설계사(FC), BANCA 채널은 판매 은행(BANK)이 수수료를 받는다.
 */
public enum SalesChannel {

    /** 설계사(FC) 대면 판매 — GA 기본 채널. */
    FC(CommissionConstants.RECIPIENT_TYPE_FC),

    /** 방카슈랑스 — 은행 창구 판매. 수수료 수령 주체는 판매 은행. */
    BANCA(CommissionConstants.RECIPIENT_TYPE_BANK);

    private final String commissionRecipientType;

    SalesChannel(String commissionRecipientType) {
        this.commissionRecipientType = commissionRecipientType;
    }

    /** 이 채널의 수수료 수령 주체 유형 (commission_schedules.recipient_type). */
    public String commissionRecipientType() {
        return commissionRecipientType;
    }
}
