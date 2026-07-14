package github.lms.lemuel.investment.domain;

import java.util.List;

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

    /**
     * 등급 밴드 테이블 — (하한 총점, 등급) 쌍을 총점 내림차순으로 나열한다({@code InvestmentScorePolicy}
     * 의 선언적 Band 테이블과 동형). if/else 사슬 대신 첫 매칭(총점 ≥ 하한) 밴드를 돌려줘, 등급 경계
     * 변경을 데이터 수정으로 국한한다. 어떤 밴드에도 걸리지 않으면 최저 등급 CCC.
     */
    private record Band(int minScore, InvestmentGrade grade) { }

    private static final List<Band> BANDS = List.of(
            new Band(90, AAA),
            new Band(80, AA),
            new Band(70, A),
            new Band(60, BBB),
            new Band(50, BB),
            new Band(40, B));

    public static InvestmentGrade fromScore(int totalScore) {
        for (Band band : BANDS) {
            if (totalScore >= band.minScore()) {
                return band.grade();
            }
        }
        return CCC;
    }
}
