package github.lms.lemuel.company.domain;

/** 기사 감성 극성. Phase 2 는 룰 기반, Phase 4 에서 LLM 구현체로 교체 예정 (ADR 0023). */
public enum Sentiment {
    POSITIVE,
    NEGATIVE,
    NEUTRAL
}
