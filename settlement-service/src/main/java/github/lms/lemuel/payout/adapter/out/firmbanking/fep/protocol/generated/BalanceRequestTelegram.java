// 이 파일은 telegram/firmbanking/*.yaml 에서 자동 생성된다 (ADR 0033 Phase 2).
// 직접 고치지 말 것 — 스펙 YAML 을 고치고 재생성한다:
//   ./gradlew :settlement-service:generateTelegramSources
package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.generated;


/**
 * 계좌 잔액조회 요청 — 전문구분코드 0100 · 총 60바이트.
 */
public record BalanceRequestTelegram(
        String msgType,
        String telegramNo,
        String transDt,
        String respCode,
        String bankCode,
        String accountNo) {
}
