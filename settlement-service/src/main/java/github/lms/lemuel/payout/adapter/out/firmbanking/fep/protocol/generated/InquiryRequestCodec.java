// 이 파일은 telegram/firmbanking/*.yaml 에서 자동 생성된다 (ADR 0033 Phase 2).
// 직접 고치지 말 것 — 스펙 YAML 을 고치고 재생성한다:
//   ./gradlew :settlement-service:generateTelegramSources
package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.generated;

import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepLayouts;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepProtocolException;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.TelegramCodecSupport;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.TelegramLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * INQUIRY_REQUEST 코덱 — 스펙에서 생성된 타입 안전 인코딩·디코딩.
 */
public final class InquiryRequestCodec {

    /** 전문 식별자 — 레이아웃은 스펙 카탈로그가 단일 출처다. */
    public static final String TELEGRAM = "INQUIRY_REQUEST";
    public static final String MSG_TYPE = "0400";
    public static final int TOTAL_LENGTH = 66;

    private static final TelegramLayout LAYOUT = FepLayouts.catalog().layout(TELEGRAM);

    private InquiryRequestCodec() {
    }

    /** 값 → 고정길이 전문 바이트. */
    public static byte[] encode(InquiryRequestTelegram telegram) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("MSG_TYPE", TelegramCodecSupport.text(telegram.msgType()));
        values.put("TELEGRAM_NO", TelegramCodecSupport.text(telegram.telegramNo()));
        values.put("TRANS_DT", TelegramCodecSupport.text(telegram.transDt()));
        values.put("RESP_CODE", TelegramCodecSupport.text(telegram.respCode()));
        values.put("ORIG_TELEGRAM_NO", TelegramCodecSupport.text(telegram.origTelegramNo()));
        values.put("REF_ID", TelegramCodecSupport.text(telegram.refId()));
        return LAYOUT.encode(values);
    }

    /**
     * 고정길이 전문 바이트 → 값.
     *
     * <p>반복부는 <b>선언된 최대 건수를 그대로</b> 돌려준다(빈 슬롯 포함). 유효 건수는 전문의
     * 건수 필드가 알려주며, 값이 비었다는 이유로 슬롯을 버리면 은행이 보낸 실패 건을 놓친다.
     */
    public static InquiryRequestTelegram decode(byte[] raw) {
        Map<String, String> values = LAYOUT.decode(raw);
        return new InquiryRequestTelegram(
                values.get("MSG_TYPE"),
                values.get("TELEGRAM_NO"),
                values.get("TRANS_DT"),
                values.get("RESP_CODE"),
                values.get("ORIG_TELEGRAM_NO"),
                values.get("REF_ID"));
    }
}
