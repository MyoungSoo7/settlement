// 이 파일은 telegram/firmbanking/*.yaml 에서 자동 생성된다 (ADR 0033).
// 직접 고치지 말 것 — 스펙 YAML 을 고치고 재생성한다:
//   ./gradlew :settlement-service:generateTelegramSources
package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.generated;

import java.math.BigDecimal;

/**
 * 계좌 잔액조회 응답 — 전문구분코드 0110 · 개정 1 · 총 95바이트.
 */
public record BalanceResponseTelegram(
        String msgType,
        String telegramNo,
        String transDt,
        String respCode,
        String bankCode,
        String accountNo,
        BigDecimal balance,
        String holderName) {
}
