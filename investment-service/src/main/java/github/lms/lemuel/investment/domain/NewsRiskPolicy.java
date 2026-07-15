package github.lms.lemuel.investment.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 악재 키워드 스캔 정책(R3) — 순수 도메인. 기사 제목·요약에 악재 키워드가 포함되면 플래그를 세운다.
 *
 * <p>키워드는 kakaopay-invest-companion 플러그인의 투자자용 악재 키워드를 이식했다.
 * "실적"은 정기 실적 기사(호재 포함)까지 잡는 오탐이 커서 서버 판정용에서는 제외한다
 * (플러그인에서는 검색어였지 판정 기준이 아니었다).
 */
public class NewsRiskPolicy {

    public static final List<String> RISK_KEYWORDS =
            List.of("유상증자", "횡령", "배임", "거래정지", "상장폐지", "소송");

    /** 기사 목록을 스캔한다 — 기사당 최초 매칭 키워드 1개만 플래그(중복 방지). */
    public NewsRiskCheck scan(List<NewsArticleSummary> articles) {
        List<NewsRiskCheck.Flag> flags = new ArrayList<>();
        for (NewsArticleSummary article : articles) {
            for (String keyword : RISK_KEYWORDS) {
                if (article.mentions(keyword)) {
                    flags.add(new NewsRiskCheck.Flag(
                            keyword, article.title(), article.url(), article.publishedAt()));
                    break;
                }
            }
        }
        return NewsRiskCheck.of(articles.size(), flags);
    }
}
