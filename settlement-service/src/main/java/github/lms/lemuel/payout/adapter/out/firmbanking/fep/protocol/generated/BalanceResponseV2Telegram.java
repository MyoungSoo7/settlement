// 이 파일은 telegram/firmbanking/*.yaml 에서 자동 생성된다 (ADR 0033).
// 직접 고치지 말 것 — 스펙 YAML 을 고치고 재생성한다:
//   ./gradlew :settlement-service:generateTelegramSources
package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.generated;

import java.math.BigDecimal;

/**
 * 계좌 잔액조회 응답 (개정 2 — 최종거래일자 추가) — 전문구분코드 0110 · 개정 2 · 총 103바이트.
 */
public record BalanceResponseV2Telegram(
        String msgType,
        String telegramNo,
        String transDt,
        String respCode,
        String bankCode,
        String accountNo,
        BigDecimal balance,
        String holderName,
        String lastTxnDt) {
}
