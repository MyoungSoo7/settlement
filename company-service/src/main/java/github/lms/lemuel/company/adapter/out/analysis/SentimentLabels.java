package github.lms.lemuel.company.adapter.out.analysis;

import github.lms.lemuel.company.domain.ArticleSentiment;
import github.lms.lemuel.company.domain.IssueCategory;

/**
 * LLM 감성분석 제공자(Claude·Gemini) 공용 — 분류 지시 프롬프트 + 라벨→도메인 매핑.
 * 제공자가 달라도 "라벨 하나만 출력" 계약과 매핑은 동일하므로 여기 모은다 (ADR 0023 Phase 4).
 */
final class SentimentLabels {

    private SentimentLabels() {
    }

    /** 분류 지시 — 라벨 하나만 출력하도록 강제. */
    static final String INSTRUCTION = """
            너는 한국 기업 뉴스 헤드라인의 평판 영향을 분류하는 분류기다.
            입력(제목/요약)에 대해 아래 라벨 중 정확히 하나만, 다른 말 없이 출력해라:
            POSITIVE, NEUTRAL, NEGATIVE_FINANCIAL, NEGATIVE_LEGAL, NEGATIVE_GOVERNANCE,
            NEGATIVE_LABOR, NEGATIVE_PRODUCT, NEGATIVE_OTHER
            부정 기사는 가장 핵심적인 이슈 카테고리를 골라라(재무/법률/지배구조/노동/제품). 애매하면 NEGATIVE_OTHER.""";

    /** 제목/요약을 사용자 입력 텍스트로. */
    static String userText(String title, String summary) {
        return "제목: " + (title == null ? "" : title)
                + "\n요약: " + (summary == null ? "" : summary);
    }

    /** 라벨 문자열 → 도메인 감성. 알 수 없는 라벨은 NEUTRAL(보수적). */
    static ArticleSentiment toSentiment(String rawLabel) {
        String label = rawLabel == null ? "" : rawLabel.trim().toUpperCase();
        return switch (label) {
            case "POSITIVE" -> ArticleSentiment.positive();
            case "NEGATIVE_FINANCIAL" -> ArticleSentiment.negative(IssueCategory.FINANCIAL);
            case "NEGATIVE_LEGAL" -> ArticleSentiment.negative(IssueCategory.LEGAL);
            case "NEGATIVE_GOVERNANCE" -> ArticleSentiment.negative(IssueCategory.GOVERNANCE);
            case "NEGATIVE_LABOR" -> ArticleSentiment.negative(IssueCategory.LABOR);
            case "NEGATIVE_PRODUCT" -> ArticleSentiment.negative(IssueCategory.PRODUCT);
            case "NEGATIVE_OTHER" -> ArticleSentiment.negative(null);
            default -> ArticleSentiment.neutral();
        };
    }
}
