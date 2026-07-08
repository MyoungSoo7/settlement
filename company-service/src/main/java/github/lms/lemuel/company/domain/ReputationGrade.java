package github.lms.lemuel.company.domain;

/** 평판 등급 — 점수 구간 매핑. */
public enum ReputationGrade {
    A, B, C, D, E;

    /** 0~100 점수를 등급으로. A≥80, B≥60, C≥40, D≥20, E<20. */
    public static ReputationGrade fromScore(int score) {
        if (score >= 80) {
            return A;
        }
        if (score >= 60) {
            return B;
        }
        if (score >= 40) {
            return C;
        }
        if (score >= 20) {
            return D;
        }
        return E;
    }
}
