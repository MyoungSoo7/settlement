package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 고정길이 전문 레이아웃 — 필드 선언 순서가 byte offset 을 결정하는 불변 규격.
 *
 * <p>인코딩은 EUC-KR: 은행 전문의 사실상 표준으로, 한글 1자가 2바이트를 차지해
 * "문자 수 != 바이트 수" 가 된다. 패딩·길이 검증은 반드시 <b>바이트 기준</b>으로 한다.
 */
public record TelegramLayout(List<FepField> fields) {

    public static final Charset EUC_KR = Charset.forName("EUC-KR");

    private static final byte SPACE = 0x20;
    private static final byte ZERO = 0x30;

    public TelegramLayout {
        if (fields == null || fields.isEmpty()) throw new FepProtocolException("필드 목록 필수");
        fields = List.copyOf(fields);
    }

    /** 전문 총 바이트 길이 (모든 필드 길이의 합). */
    public int totalLength() {
        return fields.stream().mapToInt(FepField::length).sum();
    }

    /**
     * 필드값 맵 → 고정길이 전문 바이트.
     *
     * @throws FepProtocolException 값이 필드 길이 초과, N 필드에 비숫자 포함 시
     */
    public byte[] encode(Map<String, String> values) {
        byte[] telegram = new byte[totalLength()];
        int offset = 0;
        for (FepField field : fields) {
            String value = values.getOrDefault(field.name(), "");
            byte[] valueBytes = value.getBytes(EUC_KR);
            if (valueBytes.length > field.length()) {
                throw new FepProtocolException(
                        "필드 길이 초과: " + field.name() + " (" + valueBytes.length + " > " + field.length() + " 바이트)");
            }
            if (field.type() == FepFieldType.N) {
                if (!value.chars().allMatch(Character::isDigit)) {
                    throw new FepProtocolException("N 필드에 비숫자 포함: " + field.name() + "='" + value + "'");
                }
                // 우측 정렬 + 좌측 '0' 패딩
                Arrays.fill(telegram, offset, offset + field.length() - valueBytes.length, ZERO);
                System.arraycopy(valueBytes, 0, telegram, offset + field.length() - valueBytes.length, valueBytes.length);
            } else {
                // 좌측 정렬 + 우측 공백 패딩
                System.arraycopy(valueBytes, 0, telegram, offset, valueBytes.length);
                Arrays.fill(telegram, offset + valueBytes.length, offset + field.length(), SPACE);
            }
            offset += field.length();
        }
        return telegram;
    }

    /**
     * 고정길이 전문 바이트 → 필드값 맵. AN 은 우측 공백 제거, N 은 원문 유지(선행 0 보존).
     *
     * @throws FepProtocolException 전문 길이가 레이아웃 총길이와 다를 때
     */
    public Map<String, String> decode(byte[] telegram) {
        if (telegram == null || telegram.length != totalLength()) {
            throw new FepProtocolException("전문 길이 불일치: 수신 "
                    + (telegram == null ? "null" : telegram.length) + " != 규격 " + totalLength() + " 바이트");
        }
        Map<String, String> values = new LinkedHashMap<>();
        int offset = 0;
        for (FepField field : fields) {
            String raw = new String(telegram, offset, field.length(), EUC_KR);
            values.put(field.name(), field.type() == FepFieldType.AN ? raw.stripTrailing() : raw);
            offset += field.length();
        }
        return values;
    }
}
