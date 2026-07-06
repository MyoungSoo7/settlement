package github.lms.lemuel.financial.application.port.in;

/** corp_code 를 보유한 기업 전체의 해당 연도 요약 재무제표를 DART 에서 수집해 upsert 한다. */
public interface SyncStatementsUseCase {

    /** @throws IllegalStateException DART API 키 미설정 시 */
    SyncResult syncStatements(int year);
}
