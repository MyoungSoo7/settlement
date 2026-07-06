package github.lms.lemuel.company.application.port.in;

/**
 * 수집 결과 요약.
 *
 * @param companies  수집 대상 기업 수
 * @param fetched    외부 API 가 돌려준 기사 수 (유효하지 않은 항목 포함)
 * @param saved      신규 저장된 기사 수
 * @param duplicated url_hash 멱등 키로 걸러진 중복 기사 수
 */
public record CollectResult(int companies, int fetched, int saved, int duplicated) {

    public static CollectResult merge(CollectResult a, CollectResult b) {
        return new CollectResult(
                a.companies + b.companies,
                a.fetched + b.fetched,
                a.saved + b.saved,
                a.duplicated + b.duplicated);
    }
}
