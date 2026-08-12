package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol;

/**
 * 고정길이 전문의 필드 1개 — byte offset 은 레이아웃 내 선언 순서로 결정된다.
 *
 * @param name   필드 식별자 (레이아웃 내 유일)
 * @param length 고정 바이트 길이 (EUC-KR 기준 — 한글 1자 = 2바이트)
 * @param type   패딩·정렬 규칙을 결정하는 타입
 * @param scale  금액 필드의 소수 자릿수. {@code null} 이면 <b>금액이 아닌</b> 숫자(일련번호·일시·건수)라는
 *               뜻이고, 값이 있으면 코드 생성이 이 필드를 {@code BigDecimal} 로 노출한다.
 *               런타임 코덱({@link TelegramLayout})은 이 값을 쓰지 않는다 — 전문 바이트에는 영향이 없다.
 */
public record FepField(String name, int length, FepFieldType type, Integer scale) {

    public FepField {
        if (name == null || name.isBlank()) throw new FepProtocolException("필드명 필수");
        if (length <= 0) throw new FepProtocolException("필드 길이는 1 이상: " + name);
        if (type == null) throw new FepProtocolException("필드 타입 필수: " + name);
        if (scale != null) {
            if (type != FepFieldType.N) {
                throw new FepProtocolException("scale 은 N 필드에만 쓴다: " + name);
            }
            if (scale < 0) throw new FepProtocolException("scale 은 0 이상: " + name);
        }
    }

    /** 금액이 아닌 일반 필드. */
    public FepField(String name, int length, FepFieldType type) {
        this(name, length, type, null);
    }

    /** 코드 생성이 BigDecimal 로 노출할 금액 필드인가. */
    public boolean isDecimal() {
        return scale != null;
    }
}
