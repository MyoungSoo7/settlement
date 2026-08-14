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
 * HOLDER_REQUEST 개정 1 코덱 — 스펙에서 생성된 타입 안전 인코딩·디코딩.
 */
public final class HolderRequestCodec {

    public static final String TELEGRAM = "HOLDER_REQUEST";
    public static final String MSG_TYPE = "0300";
    public static final int VERSION = 1;
    public static final int TOTAL_LENGTH = 60;

    private static final TelegramSpec SPEC = FepLayouts.catalog().spec(TELEGRAM, VERSION);

    private HolderRequestCodec() {
    }

    /** 값 → 고정길이 전문 바이트. */
    public static byte[] encode(HolderRequestTelegram telegram) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("MSG_TYPE", TelegramCodecSupport.text(telegram.msgType()));
        values.put("TELEGRAM_NO", TelegramCodecSupport.text(telegram.telegramNo()));
        values.put("TRANS_DT", TelegramCodecSupport.text(telegram.transDt()));
        values.put("RESP_CODE", TelegramCodecSupport.text(telegram.respCode()));
        values.put("BANK_CODE", TelegramCodecSupport.text(telegram.bankCode()));
        values.put("ACCOUNT_NO", TelegramCodecSupport.text(telegram.accountNo()));
        return SPEC.toLayout().encode(values);
    }

    /**
     * 고정길이 전문 바이트 → 값.
     */
    public static HolderRequestTelegram decode(byte[] raw) {
        Map<String, String> values = SPEC.toLayout().decode(raw);
        return new HolderRequestTelegram(
                values.get("MSG_TYPE"),
                values.get("TELEGRAM_NO"),
                values.get("TRANS_DT"),
                values.get("RESP_CODE"),
                values.get("BANK_CODE"),
                values.get("ACCOUNT_NO"));
    }
}
