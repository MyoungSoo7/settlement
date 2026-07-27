package github.lms.lemuel.company.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.Optional;

/**
 * 국민연금 사업장가입자 공개데이터 1건(사업장 × 월 스냅샷) — 인원수/추정연봉 조회 전용.
 *
 * <p>기존 {@link Company}(상장사 stockCode 체계)와는 무관한 독립 조회 대상이다. 원본 CSV의
 * 사업자등록번호는 앞 6자리만 공개되어 전국 단위로 고유하지 않으므로, 업무 식별은 사업장명
 * 텍스트 검색으로만 한다.
 */
public class CompanyWorkforce {

    /** 국민연금 보험료율(사용자+근로자 합산, 국민연금법 고정) — 당월고지금액에서 소득월액을 역산하는 데 쓴다. */
    private static final BigDecimal NPS_CONTRIBUTION_RATE = new BigDecimal("0.09");

    private final String workplaceName;
    private final String bizRegNoPrefix;
    private final String industryName;
    private final String address;
    private final YearMonth snapshotMonth;
    private final int headcount;
    private final BigDecimal monthlyBilledAmount;

    public CompanyWorkforce(String workplaceName, String bizRegNoPrefix, String industryName, String address,
                             YearMonth snapshotMonth, int headcount, BigDecimal monthlyBilledAmount) {
        if (workplaceName == null || workplaceName.isBlank()) {
            throw new IllegalArgumentException("사업장명은 필수입니다");
        }
        if (headcount < 0) {
            throw new IllegalArgumentException("가입자수는 음수일 수 없습니다: " + headcount);
        }
        if (monthlyBilledAmount == null || monthlyBilledAmount.signum() < 0) {
            throw new IllegalArgumentException("당월고지금액은 음수일 수 없습니다: " + monthlyBilledAmount);
        }
        this.workplaceName = workplaceName;
        this.bizRegNoPrefix = bizRegNoPrefix;
        this.industryName = industryName;
        this.address = address;
        this.snapshotMonth = snapshotMonth;
        this.headcount = headcount;
        this.monthlyBilledAmount = monthlyBilledAmount;
    }

    /**
     * 추정연봉 — 당월고지금액(사업장 전체, 국민연금 보험료 9% 대납분 합계)을 가입자수로 나눈 뒤
     * 보험료율로 역산해 1인당 월 소득월액을 구하고 12개월을 곱한다. 국민연금 기준소득월액 상한이
     * 적용되므로 고소득 구간은 실제보다 낮게 추정된다(왜곡 가능— 호출측에서 안내 문구 필요).
     */
    public Optional<BigDecimal> estimatedAnnualSalary() {
        if (headcount == 0) {
            return Optional.empty();
        }
        BigDecimal annualBilled = monthlyBilledAmount.multiply(BigDecimal.valueOf(12));
        BigDecimal denominator = BigDecimal.valueOf(headcount).multiply(NPS_CONTRIBUTION_RATE);
        return Optional.of(annualBilled.divide(denominator, 0, RoundingMode.HALF_UP));
    }

    public String workplaceName() {
        return workplaceName;
    }

    public String bizRegNoPrefix() {
        return bizRegNoPrefix;
    }

    public String industryName() {
        return industryName;
    }

    public String address() {
        return address;
    }

    public YearMonth snapshotMonth() {
        return snapshotMonth;
    }

    public int headcount() {
        return headcount;
    }

    public BigDecimal monthlyBilledAmount() {
        return monthlyBilledAmount;
    }
}
