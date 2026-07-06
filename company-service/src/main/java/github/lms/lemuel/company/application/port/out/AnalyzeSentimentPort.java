package github.lms.lemuel.company.application.port.out;

import github.lms.lemuel.company.domain.ArticleSentiment;

/**
 * 기사 감성 분석 포트 — Phase 2 구현체는 룰 기반(KeywordSentimentAnalyzer),
 * Phase 4 에서 LLM(Claude API) 구현체로 무중단 교체 가능하게 인터페이스 뒤에 숨긴다(ADR 0023).
 */
public interface AnalyzeSentimentPort {

    ArticleSentiment analyze(String title, String summary);
}
