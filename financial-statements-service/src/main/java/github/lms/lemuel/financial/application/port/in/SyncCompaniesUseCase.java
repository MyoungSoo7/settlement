package github.lms.lemuel.financial.application.port.in;

/** DART 에서 코스피 상장사 목록을 수집해 companies 를 upsert 한다. */
public interface SyncCompaniesUseCase {

    /** @throws IllegalStateException DART API 키 미설정 시 */
    SyncResult syncCompanies();
}
