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
 * BULK_TRANSFER_REQUEST 코덱 — 스펙에서 생성된 타입 안전 인코딩·디코딩.
 */
public final class BulkTransferRequestCodec {

    /** 전문 식별자 — 레이아웃은 스펙 카탈로그가 단일 출처다. */
    public static final String TELEGRAM = "BULK_TRANSFER_REQUEST";
    public static final String MSG_TYPE = "0220";
    public static final int TOTAL_LENGTH = 462;

    /** 반복부 DETAIL 최대 건수. */
    public static final int DETAIL_MAX = 5;

    private static final TelegramLayout LAYOUT = FepLayouts.catalog().layout(TELEGRAM);

    private BulkTransferRequestCodec() {
    }

    /** 값 → 고정길이 전문 바이트. */
    public static byte[] encode(BulkTransferRequestTelegram telegram) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("MSG_TYPE", TelegramCodecSupport.text(telegram.msgType()));
        values.put("TELEGRAM_NO", TelegramCodecSupport.text(telegram.telegramNo()));
        values.put("TRANS_DT", TelegramCodecSupport.text(telegram.transDt()));
        values.put("RESP_CODE", TelegramCodecSupport.text(telegram.respCode()));
        values.put("TOTAL_CNT", TelegramCodecSupport.text(telegram.totalCnt()));
        values.put("TOTAL_AMOUNT", TelegramCodecSupport.digits(telegram.totalAmount(), 0, "TOTAL_AMOUNT"));
        List<BulkTransferRequestTelegram.Detail> details = telegram.details() == null ? List.of() : telegram.details();
        if (details.size() > DETAIL_MAX) {
            throw new FepProtocolException("반복부 DETAIL 최대 " + DETAIL_MAX + "건 초과: " + details.size());
        }
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
        return LAYOUT.encode(values);
    }

    /**
     * 고정길이 전문 바이트 → 값.
     *
     * <p>반복부는 <b>선언된 최대 건수를 그대로</b> 돌려준다(빈 슬롯 포함). 유효 건수는 전문의
     * 건수 필드가 알려주며, 값이 비었다는 이유로 슬롯을 버리면 은행이 보낸 실패 건을 놓친다.
     */
    public static BulkTransferRequestTelegram decode(byte[] raw) {
        Map<String, String> values = LAYOUT.decode(raw);
        List<BulkTransferRequestTelegram.Detail> details = new ArrayList<>();
        for (int i = 1; i <= DETAIL_MAX; i++) {
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
