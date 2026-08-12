// 이 파일은 telegram/firmbanking/*.yaml 에서 자동 생성된다 (ADR 0033).
// 직접 고치지 말 것 — 스펙 YAML 을 고치고 재생성한다:
//   ./gradlew :settlement-service:generateTelegramSources
package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.generated;

import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepLayouts;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepProtocolException;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.TelegramCodecSupport;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec.TelegramSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * INQUIRY_RESPONSE 개정 1 코덱 — 스펙에서 생성된 타입 안전 인코딩·디코딩.
 */
public final class InquiryResponseCodec {

    public static final String TELEGRAM = "INQUIRY_RESPONSE";
    public static final String MSG_TYPE = "0410";
    public static final int VERSION = 1;
    public static final int TOTAL_LENGTH = 91;

    private static final TelegramSpec SPEC = FepLayouts.catalog().spec(TELEGRAM, VERSION);

    private InquiryResponseCodec() {
    }

    /** 값 → 고정길이 전문 바이트. */
    public static byte[] encode(InquiryResponseTelegram telegram) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("MSG_TYPE", TelegramCodecSupport.text(telegram.msgType()));
        values.put("TELEGRAM_NO", TelegramCodecSupport.text(telegram.telegramNo()));
        values.put("TRANS_DT", TelegramCodecSupport.text(telegram.transDt()));
        values.put("RESP_CODE", TelegramCodecSupport.text(telegram.respCode()));
        values.put("ORIG_TELEGRAM_NO", TelegramCodecSupport.text(telegram.origTelegramNo()));
        values.put("REF_ID", TelegramCodecSupport.text(telegram.refId()));
        values.put("RESULT", TelegramCodecSupport.text(telegram.result()));
        values.put("TXN_ID", TelegramCodecSupport.text(telegram.txnId()));
        values.put("ERROR_CODE", TelegramCodecSupport.text(telegram.errorCode()));
        return SPEC.toLayout().encode(values);
    }

    /**
     * 고정길이 전문 바이트 → 값.
     */
    public static InquiryResponseTelegram decode(byte[] raw) {
        Map<String, String> values = SPEC.toLayout().decode(raw);
        return new InquiryResponseTelegram(
                values.get("MSG_TYPE"),
                values.get("TELEGRAM_NO"),
                values.get("TRANS_DT"),
                values.get("RESP_CODE"),
                values.get("ORIG_TELEGRAM_NO"),
                values.get("REF_ID"),
                values.get("RESULT"),
                values.get("TXN_ID"),
                values.get("ERROR_CODE"));
    }
}
