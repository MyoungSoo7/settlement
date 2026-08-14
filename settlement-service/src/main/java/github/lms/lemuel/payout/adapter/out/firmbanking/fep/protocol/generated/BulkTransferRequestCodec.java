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
 * BULK_TRANSFER_REQUEST 개정 1 코덱 — 스펙에서 생성된 타입 안전 인코딩·디코딩.
 */
public final class BulkTransferRequestCodec {

    public static final String TELEGRAM = "BULK_TRANSFER_REQUEST";
    public static final String MSG_TYPE = "0220";
    public static final int VERSION = 1;
    /** 건수와 무관하게 확정된 선두 길이. */
    public static final int BASE_LENGTH = 52;
    /** 반복부 DETAIL 최대 건수. */
    public static final int DETAIL_MAX = 100;

    private static final TelegramSpec SPEC = FepLayouts.catalog().spec(TELEGRAM, VERSION);

    private BulkTransferRequestCodec() {
    }

    /** 값 → 고정길이 전문 바이트. */
    public static byte[] encode(BulkTransferRequestTelegram telegram) {
        Map<String, String> values = new LinkedHashMap<>();
        List<BulkTransferRequestTelegram.Detail> details = telegram.details() == null ? List.of() : telegram.details();
        if (details.size() > DETAIL_MAX) {
            throw new FepProtocolException("반복부 DETAIL 최대 " + DETAIL_MAX + "건 초과: " + details.size());
        }
        values.put("MSG_TYPE", TelegramCodecSupport.text(telegram.msgType()));
        values.put("TELEGRAM_NO", TelegramCodecSupport.text(telegram.telegramNo()));
        values.put("TRANS_DT", TelegramCodecSupport.text(telegram.transDt()));
        values.put("RESP_CODE", TelegramCodecSupport.text(telegram.respCode()));
        values.put("TOTAL_CNT", TelegramCodecSupport.count(telegram.totalCnt(), details.size(), "TOTAL_CNT"));
        values.put("TOTAL_AMOUNT", TelegramCodecSupport.digits(telegram.totalAmount(), 0, "TOTAL_AMOUNT"));
        for (int i = 0; i < details.size(); i++) {
            var item = details.get(i);
            String prefix = "DETAIL_" + (i + 1) + "_";
            values.put(prefix + "SEQ", TelegramCodecSupport.text(item.seq()));
            values.put(prefix + "BANK_CODE", TelegramCodecSupport.text(item.bankCode()));
            values.put(prefix + "ACCOUNT_NO", TelegramCodecSupport.text(item.accountNo()));
            values.put(prefix + "AMOUNT", TelegramCodecSupport.digits(item.amount(), 0, "AMOUNT"));
            values.put(prefix + "HOLDER_NAME", TelegramCodecSupport.text(item.holderName()));
            values.put(prefix + "REF_ID", TelegramCodecSupport.text(item.refId()));
        }
        return SPEC.layoutFor(details.size()).encode(values);
    }

    /**
     * 고정길이 전문 바이트 → 값.
     *
     * <p>반복 건수는 건수 필드(TOTAL_CNT)를 먼저 읽어 정한다 — 길이가 건수에 따라 달라지므로
     * 레이아웃을 만들기 전에 건수를 알아야 한다.
     */
    public static BulkTransferRequestTelegram decode(byte[] raw) {
        int occurrences = SPEC.readOccurrences(raw);
        Map<String, String> values = SPEC.layoutFor(occurrences).decode(raw);
        List<BulkTransferRequestTelegram.Detail> details = new ArrayList<>();
        for (int i = 1; i <= occurrences; i++) {
            String prefix = "DETAIL_" + i + "_";
            details.add(new BulkTransferRequestTelegram.Detail(
                    values.get(prefix + "SEQ"),
                    values.get(prefix + "BANK_CODE"),
                    values.get(prefix + "ACCOUNT_NO"),
                    TelegramCodecSupport.decimal(values.get(prefix + "AMOUNT"), 0, "AMOUNT"),
                    values.get(prefix + "HOLDER_NAME"),
                    values.get(prefix + "REF_ID")));
        }
        return new BulkTransferRequestTelegram(
                values.get("MSG_TYPE"),
                values.get("TELEGRAM_NO"),
                values.get("TRANS_DT"),
                values.get("RESP_CODE"),
                values.get("TOTAL_CNT"),
                TelegramCodecSupport.decimal(values.get("TOTAL_AMOUNT"), 0, "TOTAL_AMOUNT"),
                List.copyOf(details));
    }
}
