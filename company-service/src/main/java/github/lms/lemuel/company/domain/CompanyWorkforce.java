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

    /** 업종코드 롤업 단위 — 국세청 업종코드 연계표 기준 앞 3자리. */
    private static final int INDUSTRY_ROLLUP_LENGTH = 3;

    private final String workplaceName;
    private final String bizRegNoPrefix;
    private final String industryCode;
    private final String industryName;
    private final String address;
    private final YearMonth snapshotMonth;
    private final int headcount;
    private final BigDecimal monthlyBilledAmount;

    public CompanyWorkforce(String workplaceName, String bizRegNoPrefix, String industryCode, String industryName,
                             String address, YearMonth snapshotMonth, int headcount, BigDecimal monthlyBilledAmount) {
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
        this.industryCode = industryCode;
        this.industryName = industryName;
        this.address = address;
        this.snapshotMonth = snapshotMonth;
        this.headcount = headcount;
        this.monthlyBilledAmount = monthlyBilledAmount;
    }

    /**
     * 추정연봉 — 당월고지금액(사업장 전체, 기준월 적용 국민연금 보험료 대납분 합계)을 가입자수로 나눈 뒤
     * 기준월 적용 보험료율로 역산해 1인당 월 소득월액을 구하고 12개월을 곱한다. 국민연금 기준소득월액 상한이
     * 적용되므로 고소득 구간은 실제보다 낮게 추정된다(왜곡 가능— 호출측에서 안내 문구 필요).
     */
    public Optional<BigDecimal> estimatedAnnualSalary() {
        if (headcount == 0) {
            return Optional.empty();
        }
        BigDecimal annualBilled = monthlyBilledAmount.multiply(BigDecimal.valueOf(12));
        return NpsContributionRate.rateOf(snapshotMonth)
                .map(contributionRate -> annualBilled.divide(
                        BigDecimal.valueOf(headcount).multiply(contributionRate), 0, RoundingMode.HALF_UP));
    }

    /**
     * 세부 업종 집단 키 = 원본 CSV 의 6자리 사업장 업종코드. 미신고 사업장은 공란이라 null·빈 문자열이
     * 실제로 온다("사업장의 미신고로 업종코드 등 공란존재" — 원문 유의사항) — 이때 업종 비교는
     * {@link ComparisonUnavailableReason#INDUSTRY_CODE_MISSING} 으로 떨어진다.
     */
    public Optional<String> industryGroupKey() {
        return industryCode == null || industryCode.isBlank()
                ? Optional.empty() : Optional.of(industryCode.strip());
    }

    /**
     * 상위 업종 집단 키(앞 3자리, 국세청 업종코드 연계표의 롤업 단위) — 세부 업종 표본이 미달일 때
     * 한 단계 넓히는 대상. 코드가 3자리 이하면 코드 자체가 상위 키다.
     */
    public Optional<String> industryRollupKey() {
        return industryGroupKey().map(code -> code.length() <= INDUSTRY_ROLLUP_LENGTH
                ? code : code.substring(0, INDUSTRY_ROLLUP_LENGTH));
    }

    /**
     * 지표별 대상 값. 추정연봉은 산출 불가(가입자수 0)일 수 있어 Optional 이다 — 호출측이 지표별
     * 매핑을 알 필요 없게 도메인이 답한다.
     */
    public Optional<BigDecimal> valueOf(WorkforceMetric metric) {
        return switch (metric) {
            case HEADCOUNT -> Optional.of(BigDecimal.valueOf(headcount));
            case ESTIMATED_ANNUAL_SALARY -> estimatedAnnualSalary();
        };
    }

    /** 주소에서 파생한 지역 집단. */
    public WorkplaceRegion region() {
        return WorkplaceRegion.parse(address);
    }

    /**
     * 추정연봉이 기준소득월액 상한액의 12배 이상인지 — 비교 성패·사유 코드와 무관하게 항상 제공되는
     * 신뢰도 플래그다. 상한에 걸린 사업장은 고지금액이 같은 값에 몰려 백분위 해석력이 떨어진다.
     *
     * <p>고시표 범위 밖 기준월은 판정 근거가 없어 false 이고, 그 사실은
     * {@link #salaryCapMonthlyAmount()} 가 빈 값인 것으로 드러난다(없는 상한을 추정하지 않는다).
     */
    public boolean salaryCapReached() {
        Optional<BigDecimal> estimated = estimatedAnnualSalary();
        Optional<BigDecimal> annualCap = NpsIncomeCap.annualCapOf(snapshotMonth);
        return estimated.isPresent() && annualCap.isPresent()
                && estimated.get().compareTo(annualCap.get()) >= 0;
    }

    public Optional<BigDecimal> salaryCapMonthlyAmount() {
        return NpsIncomeCap.monthlyCapOf(snapshotMonth);
    }

    /**
     * 집계 모집단 적격 여부. 추정연봉을 산출할 수 없는 행(가입자수 0 또는 당월고지금액 0)은 두 지표 중
     * 하나를 만들 수 없으므로 중앙값·백분위 모집단에서 제외한다 — 표본수 대사의 기준도 이 판정이다.
     */
    public boolean eligibleForComparison() {
        return headcount > 0 && monthlyBilledAmount.signum() > 0;
    }

    public String workplaceName() {
        return workplaceName;
    }

    public String industryCode() {
        return industryCode;
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
