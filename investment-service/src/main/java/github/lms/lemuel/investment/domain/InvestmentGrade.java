package github.lms.lemuel.investment.domain;

/**
 * 투자점수 등급. 총점(0~100)을 결정적으로 등급으로 매핑한다.
 *
 * <pre>
 * ≥90 AAA · ≥80 AA · ≥70 A · ≥60 BBB · ≥50 BB · ≥40 B · &lt;40 CCC
 * </pre>
 *
 * <p>BBB 이상(총점 ≥60)이면 투자 적격(investable)으로 간주한다.
 */
public enum InvestmentGrade {
    AAA,
    AA,
    A,
    BBB,
    BB,
    B,
    CCC;

    public static InvestmentGrade fromScore(int totalScore) {
        if (totalScore >= 90) {
            return AAA;
        }
        if (totalScore >= 80) {
            return AA;
        }
        if (totalScore >= 70) {
            return A;
        }
        if (totalScore >= 60) {
            return BBB;
        }
        if (totalScore >= 50) {
            return BB;
        }
        if (totalScore >= 40) {
            return B;
        }
        return CCC;
    }
}
