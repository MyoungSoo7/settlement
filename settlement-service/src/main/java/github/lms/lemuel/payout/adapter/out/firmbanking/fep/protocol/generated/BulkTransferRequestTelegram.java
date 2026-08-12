// 이 파일은 telegram/firmbanking/*.yaml 에서 자동 생성된다 (ADR 0033 Phase 2).
// 직접 고치지 말 것 — 스펙 YAML 을 고치고 재생성한다:
//   ./gradlew :settlement-service:generateTelegramSources
package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.generated;

import java.math.BigDecimal;
import java.util.List;

/**
 * 다건 지급이체 요청 — 전문구분코드 0220 · 총 462바이트.
 */
public record BulkTransferRequestTelegram(
        String msgType,
        String telegramNo,
        String transDt,
        String respCode,
        String totalCnt,
        BigDecimal totalAmount,
        List<Detail> details) {

    /** 반복부 DETAIL 1건 — 최대 5건. */
    public record Detail(
            String seq,
            String bankCode,
            String accountNo,
            BigDecimal amount,
            String holderName,
            String refId) {
    }
}
