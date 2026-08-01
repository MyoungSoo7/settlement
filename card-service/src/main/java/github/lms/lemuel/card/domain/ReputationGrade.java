package github.lms.lemuel.card.domain;

import java.math.BigDecimal;

/**
 * 평판 등급별 haircut 계수. loan-service CreditPolicy 와 같은 축을 쓴다.
 * E 는 0.0 — 계수 곱의 결과가 0 이 되어 심사에서 탈락한다(별도 분기 없이 산식이 걸러낸다).
 */
public enum ReputationGrade {

    A(new BigDecimal("1.00")),
    B(new BigDecimal("1.00")),
    C(new BigDecimal("0.85")),
    D(new BigDecimal("0.70")),
    E(BigDecimal.ZERO);

    private final BigDecimal haircut;

    ReputationGrade(BigDecimal haircut) {
        this.haircut = haircut;
    }

    public BigDecimal haircut() {
        return haircut;
    }

    /** 프로젝션에 평판이 아직 없는 조직은 가장 보수적인 등급으로 본다. */
    public static ReputationGrade unknownDefault() {
        return D;
    }
}
