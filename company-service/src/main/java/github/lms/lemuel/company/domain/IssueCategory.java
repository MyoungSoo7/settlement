package github.lms.lemuel.company.domain;

/**
 * 부정 기사 이슈 분류 — 평판 점수 산정 시 카테고리별 가중치를 부여한다(ADR 0023).
 *
 * <p>가중치가 큰 카테고리(재무·법률·지배구조)일수록 여신 리스크에 직접적이라 점수를 더 깎는다.
 */
public enum IssueCategory {
    FINANCIAL(3),   // 분식·적자·부도·횡령
    LEGAL(3),       // 소송·기소·과징금·담합
    GOVERNANCE(3),  // 오너리스크·회계부정·내부통제
    LABOR(2),       // 파업·산재·중대재해·임금체불
    PRODUCT(2);     // 리콜·결함·화재·하자

    /** 부정 카테고리 없는 일반 부정 기사의 기본 가중치. */
    public static final int UNCATEGORIZED_WEIGHT = 1;
    /** 최대 카테고리 가중치 — 점수 정규화 분모에 쓰인다. */
    public static final int MAX_WEIGHT = 3;

    private final int weight;

    IssueCategory(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }
}
