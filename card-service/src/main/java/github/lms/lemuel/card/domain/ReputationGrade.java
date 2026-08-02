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

    /**
     * 이벤트 페이로드의 등급 문자열을 파싱한다. {@link #unknownDefault()}("평판 정보가 아직 없음")과
     * 달리 <b>계약 밖 값</b>은 상류 계약 위반이므로 조용히 D 로 뭉개지 않고 거부한다 —
     * 적재 시점에 막아 DLT 에서 보이게 하는 편이, 심사가 haircut 을 못 구해 500 이 나는 것보다 낫다.
     */
    public static ReputationGrade from(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("평판 등급이 없습니다 — company 이벤트 계약 위반");
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "알 수 없는 평판 등급입니다: " + raw + " (허용: A|B|C|D|E)", e);
        }
    }
}
