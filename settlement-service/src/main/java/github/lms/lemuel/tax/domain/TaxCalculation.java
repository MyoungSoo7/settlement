package github.lms.lemuel.tax.domain;

import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 세무 계산 값 객체(Value Object) — 정산 1건의 부가세·원천징수·실지급액을 도메인 규칙으로 산출한다.
 *
 * <p>순수 POJO(프레임워크 의존 0)·팩토리 전용·불변. 모든 세무 금액은 {@link TaxRounding} 원단위 절사로
 * 산출하며, 산출 직후 대사 항등식을 자가검증한다(ADR 0027, 2026-07-24 정정 — 독립 GL 감사 HIGH #4 봉합).
 *
 * <pre>
 * (부가세 — 포함과세) vatAmount     = floor(commission × 10/110)     (수수료는 부가세 포함 금액)
 *                    supplyAmount  = commission − vatAmount         (세금계산서 공급가액)
 * (원천징수)          withholdingAmount = 개인 ? floor(netAmount × 0.033) : 0
 *                    netPayable    = netAmount − withholdingAmount  (셀러 실지급 — payout 에서 실제 공제)
 * </pre>
 *
 * <p><b>2026-07-24 정정 — 두 가지 봉합</b>:
 * <ol>
 *   <li>VAT 를 별도 청구(외부과세)가 아니라 <b>포함과세</b>로 바꿔, 수수료(commission)를 부가세 포함 총액으로
 *       재해석했다. 과거 모델(commission × 0.10 외부과세, AR 별도 인식)은 실제로 청구되지 않는 미수금을
 *       무한히 쌓는 결함이 있었다(부가세는 수수료 안에서 갈라내는 것이지, 별도로 받는 게 아니다).</li>
 *   <li>원천징수는 이제 <b>실제 지급액에서 공제</b>된다({@code SettlementConfirmItemWriter} 가 payout 금액을
 *       {@code net − holdback − offset − withholding} 으로 산정) — 과거엔 장부(세무 전표)만 줄이고 실제
 *       송금은 전액 나가던 HIGH #4 결함(독립 GL 감사)이 있었다.</li>
 * </ol>
 */
public final class TaxCalculation {

    /** 부가세 포함과세 비율의 분자(10/110 중 10) — 상수화해 계산식의 의미를 코드에 남긴다. */
    private static final BigDecimal VAT_INCLUSIVE_NUMERATOR = BigDecimal.TEN;
    /** 부가세 포함과세 비율의 분모(10/110 중 110). */
    private static final BigDecimal VAT_INCLUSIVE_DENOMINATOR = new BigDecimal("110");
    /** 나눗셈 중간값의 여유 스케일 — 이후 {@link TaxRounding#floorToWon} 이 0 스케일로 절사하므로,
     *  스케일이 충분히 크고(rounding 오차 없음) DOWN 방향이 같기만 하면 최종 결과는 동일하다. */
    private static final int INTERMEDIATE_SCALE = 10;
    /** 개인(비사업자) 사업소득 원천징수율 3.3%(소득세 3% + 지방소득세 0.3%). */
    public static final BigDecimal WITHHOLDING_RATE = new BigDecimal("0.033");

    private final TaxType taxType;
    private final BigDecimal commission;        // 원값(부가세 포함 수수료 총액)
    private final BigDecimal supplyAmount;      // = commission − vatAmount (세금계산서 공급가액)
    private final BigDecimal vatAmount;         // 부가세 예수 (원단위 절사)
    private final BigDecimal netAmount;         // 정산 실지급 대상 net (원값)
    private final BigDecimal withholdingAmount; // 원천징수 예수 (원단위 절사, 개인만)
    private final BigDecimal netPayable;        // 셀러 실지급 = net − withholding

    private TaxCalculation(TaxType taxType, BigDecimal commission, BigDecimal supplyAmount, BigDecimal vatAmount,
                           BigDecimal netAmount, BigDecimal withholdingAmount, BigDecimal netPayable) {
        this.taxType = taxType;
        this.commission = commission;
        this.supplyAmount = supplyAmount;
        this.vatAmount = vatAmount;
        this.netAmount = netAmount;
        this.withholdingAmount = withholdingAmount;
        this.netPayable = netPayable;
    }

    /**
     * 정산 금액·세무유형으로 세무 계산을 산출한다.
     *
     * @param commission 플랫폼 수수료(부가세 <b>포함</b> 총액). null·음수 불가.
     * @param netAmount  정산 실지급 대상 순액. null·음수 불가.
     * @param taxType    셀러 세무유형(개인/사업자). null 불가.
     */
    public static TaxCalculation of(BigDecimal commission, BigDecimal netAmount, TaxType taxType) {
        if (taxType == null) {
            throw new TaxInvariantViolationException("taxType 은 필수입니다");
        }
        if (commission == null || commission.signum() < 0) {
            throw new TaxInvariantViolationException("commission 은 음수일 수 없습니다: " + commission);
        }
        if (netAmount == null || netAmount.signum() < 0) {
            throw new TaxInvariantViolationException("netAmount 은 음수일 수 없습니다: " + netAmount);
        }

        BigDecimal vat = vatInclusiveOf(commission);
        BigDecimal supply = commission.subtract(vat);
        BigDecimal withholding = computeWithholding(netAmount, taxType);
        BigDecimal payable = netAmount.subtract(withholding);

        TaxCalculation calc = new TaxCalculation(taxType, commission, supply, vat, netAmount, withholding, payable);
        calc.validateIdentities();
        return calc;
    }

    /**
     * 부가세 포함과세 산출 — {@code floor(commission × 10/110)}. 세무 라운딩은 원단위 절사만 허용하므로
     * (Money HALF_UP 경유 금지) 중간 나눗셈을 넉넉한 스케일(DOWN)로 truncate 한 뒤 {@link TaxRounding} 으로
     * 다시 0 스케일 절사한다 — 두 단계 모두 0 방향 절사(DOWN)이고 금액이 음수가 아니므로 한 번에
     * {@code floor} 하는 것과 결과가 같다(단조성: 더 넓은 스케일에서 절사한 값을 다시 절사해도 원래 정수
     * 절사와 동일).
     */
    private static BigDecimal vatInclusiveOf(BigDecimal commission) {
        BigDecimal raw = commission.multiply(VAT_INCLUSIVE_NUMERATOR)
                .divide(VAT_INCLUSIVE_DENOMINATOR, INTERMEDIATE_SCALE, RoundingMode.DOWN);
        return TaxRounding.floorToWon(raw);
    }

    /**
     * 원천징수액 단독 계산 — commission 없이 payout 금액 산정 시점(정산 확정)에서도 재사용할 수 있도록
     * {@link #of} 밖으로 뺀 공용 헬퍼다. 사업자·법인은 0.
     *
     * @throws TaxInvariantViolationException netAmount·taxType 이 유효하지 않은 경우
     */
    public static BigDecimal computeWithholding(BigDecimal netAmount, TaxType taxType) {
        if (taxType == null) {
            throw new TaxInvariantViolationException("taxType 은 필수입니다");
        }
        if (netAmount == null || netAmount.signum() < 0) {
            throw new TaxInvariantViolationException("netAmount 은 음수일 수 없습니다: " + netAmount);
        }
        return taxType.isWithholdingApplicable()
                ? TaxRounding.floorToWon(netAmount.multiply(WITHHOLDING_RATE))
                : BigDecimal.ZERO;
    }

    /** 대사 항등식 자가검증 — 산출 결과가 ADR 0027 항등식을 만족하는지 구성적으로 확인한다. */
    private void validateIdentities() {
        if (vatAmount.signum() < 0 || withholdingAmount.signum() < 0) {
            throw new TaxInvariantViolationException("세무 예수금은 음수일 수 없습니다");
        }
        // 포함과세 항등식 — 공급가액 + 세액 = 수수료(원 총액).
        if (supplyAmount.add(vatAmount).compareTo(commission) != 0) {
            throw new TaxInvariantViolationException("부가세 포함과세 항등식 위반: 공급가액+세액 ≠ commission");
        }
        // 실지급 = net − withholding (부가세는 실지급에서 차감하지 않는다 — 수수료 쪽 항목).
        if (netPayable.compareTo(netAmount.subtract(withholdingAmount)) != 0) {
            throw new TaxInvariantViolationException("실지급액 항등식 위반");
        }
        // 원천징수는 실지급을 음수로 만들 수 없다.
        if (netPayable.signum() < 0) {
            throw new TaxInvariantViolationException("원천징수액이 순정산액을 초과합니다");
        }
        // 사업자는 원천징수 0.
        if (!taxType.isWithholdingApplicable() && withholdingAmount.signum() != 0) {
            throw new TaxInvariantViolationException("사업자 셀러는 원천징수 대상이 아닙니다");
        }
    }

    public TaxType taxType() {
        return taxType;
    }

    /** 플랫폼 수수료(부가세 포함 총액, 원값). */
    public BigDecimal commission() {
        return commission;
    }

    /** 세금계산서 공급가액(= commission − vatAmount, 부가세 제외 순수수료). */
    public BigDecimal supplyAmount() {
        return supplyAmount;
    }

    /** 부가세 예수액(원단위 절사, 포함과세 10/110). */
    public BigDecimal vatAmount() {
        return vatAmount;
    }

    public BigDecimal netAmount() {
        return netAmount;
    }

    /** 원천징수 예수액(원단위 절사, 개인만 > 0). */
    public BigDecimal withholdingAmount() {
        return withholdingAmount;
    }

    /** 셀러 실지급액 = net − withholding. */
    public BigDecimal netPayable() {
        return netPayable;
    }

    /** 세금계산서 합계 = 공급가액 + 세액 = commission(포함과세 항등식). */
    public BigDecimal invoiceTotal() {
        return supplyAmount.add(vatAmount);
    }

    public boolean hasVat() {
        return vatAmount.signum() > 0;
    }

    public boolean hasWithholding() {
        return withholdingAmount.signum() > 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TaxCalculation other)) {
            return false;
        }
        return taxType == other.taxType
                && commission.compareTo(other.commission) == 0
                && supplyAmount.compareTo(other.supplyAmount) == 0
                && vatAmount.compareTo(other.vatAmount) == 0
                && netAmount.compareTo(other.netAmount) == 0
                && withholdingAmount.compareTo(other.withholdingAmount) == 0
                && netPayable.compareTo(other.netPayable) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(taxType, commission.stripTrailingZeros(), supplyAmount.stripTrailingZeros(),
                vatAmount.stripTrailingZeros(), netAmount.stripTrailingZeros(),
                withholdingAmount.stripTrailingZeros(), netPayable.stripTrailingZeros());
    }
}
