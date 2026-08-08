package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol;

/**
 * 고정길이 전문의 필드 1개 — byte offset 은 레이아웃 내 선언 순서로 결정된다.
 *
 * @param name   필드 식별자 (레이아웃 내 유일)
 * @param length 고정 바이트 길이 (EUC-KR 기준 — 한글 1자 = 2바이트)
 * @param type   패딩·정렬 규칙을 결정하는 타입
 */
public record FepField(String name, int length, FepFieldType type) {

    public FepField {
        if (name == null || name.isBlank()) throw new FepProtocolException("필드명 필수");
        if (length <= 0) throw new FepProtocolException("필드 길이는 1 이상: " + name);
        if (type == null) throw new FepProtocolException("필드 타입 필수: " + name);
    }
}
