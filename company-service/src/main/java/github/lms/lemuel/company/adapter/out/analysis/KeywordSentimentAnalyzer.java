package github.lms.lemuel.company.adapter.out.analysis;

import github.lms.lemuel.company.application.port.out.AnalyzeSentimentPort;
import github.lms.lemuel.company.domain.ArticleSentiment;
import github.lms.lemuel.company.domain.IssueCategory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 룰 기반 감성 분석기 (ADR 0023 Phase 2 v1).
 *
 * <p>제목+요약 텍스트에서 부정 키워드를 먼저 찾는다 — 하나라도 걸리면 NEGATIVE 이고, 그 키워드의
 * 이슈 카테고리를 붙인다(카테고리 우선순위는 enum 순서: FINANCIAL→LEGAL→GOVERNANCE→LABOR→PRODUCT).
 * 부정이 없고 긍정 키워드가 있으면 POSITIVE, 둘 다 없으면 NEUTRAL.
 *
 * <p>기본 감성 분석기다({@code app.company.sentiment.provider} 미설정 또는 keyword). Phase 4 에서
 * {@code provider=llm} 으로 두면 {@link LlmSentimentAnalyzer} 가 대신 등록되고, 이 클래스는 그 폴백이 된다
 * — {@link AnalyzeSentimentPort} 뒤에서 무중단 교체된다.
 */
@Component
@ConditionalOnProperty(name = "app.company.sentiment.provider", havingValue = "keyword", matchIfMissing = true)
public class KeywordSentimentAnalyzer implements AnalyzeSentimentPort {

    // 카테고리별 부정 키워드. enum 순서대로 검사해 첫 매칭 카테고리가 이긴다(중복 키워드 대비).
    private static final Map<IssueCategory, List<String>> NEGATIVE = new LinkedHashMap<>();

    static {
        NEGATIVE.put(IssueCategory.FINANCIAL, List.of(
                "분식", "적자", "손실", "부도", "파산", "자본잠식", "횡령", "실적악화", "어닝쇼크", "급락", "채무불이행"));
        NEGATIVE.put(IssueCategory.LEGAL, List.of(
                "소송", "고소", "고발", "기소", "압수수색", "검찰", "벌금", "과징금", "담합", "제재", "유죄", "위법"));
        NEGATIVE.put(IssueCategory.GOVERNANCE, List.of(
                "오너리스크", "회계부정", "배임", "내부통제", "도덕적해이", "지배구조", "일감몰아주기"));
        NEGATIVE.put(IssueCategory.LABOR, List.of(
                "파업", "산재", "중대재해", "임금체불", "사망사고", "해고", "갑질", "직장내괴롭힘"));
        NEGATIVE.put(IssueCategory.PRODUCT, List.of(
                "리콜", "결함", "불량", "화재", "폭발", "하자", "안전사고", "논란"));
    }

    private static final List<String> POSITIVE = List.of(
            "흑자", "흑자전환", "최대실적", "사상최대", "신기록", "호실적", "수주", "성장", "급등",
            "신제품", "투자유치", "계약체결", "수출증가", "실적개선", "역대최대");

    @Override
    public ArticleSentiment analyze(String title, String summary) {
        String text = ((title == null ? "" : title) + " " + (summary == null ? "" : summary))
                .replace(" ", "");
        for (Map.Entry<IssueCategory, List<String>> entry : NEGATIVE.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (text.contains(keyword)) {
                    return ArticleSentiment.negative(entry.getKey());
                }
            }
        }
        for (String keyword : POSITIVE) {
            if (text.contains(keyword)) {
                return ArticleSentiment.positive();
            }
        }
        return ArticleSentiment.neutral();
    }
}
