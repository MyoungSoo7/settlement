package github.lms.lemuel.loan.adapter.out.metrics;

import github.lms.lemuel.loan.application.port.out.LoanMetricsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * {@link LoanMetricsPort} 의 Micrometer 구현 (settlement 의 관측 지표 스타일 재사용).
 *
 * <p>노출 지표(Prometheus 변환명):
 * <ul>
 *   <li>{@code loan_advance_requested_total} — 선정산 대출 신청 성공</li>
 *   <li>{@code loan_advance_disbursed_total} — 선정산 대출 실행 성공</li>
 *   <li>{@code loan_advance_rejected_total} — 실행 시점 담보부족 승인 거절</li>
 *   <li>{@code loan_corporate_requested_total} — 기업 신용대출 신청 성공</li>
 *   <li>{@code loan_corporate_rejected_total} — 기업 신용평가 거절(거절률 산정 분자)</li>
 *   <li>{@code loan_corporate_disbursed_total} — 기업 신용대출 실행 성공</li>
 *   <li>{@code loan_repayment_applied_total} — 상환 차감 적용 건수</li>
 *   <li>{@code loan_repayment_amount_total} — 상환 차감 적용 금액 합계</li>
 * </ul>
 *
 * <p>카운터는 생성자에서 미리 등록해 첫 이벤트 이전에도 0 값이 스크레이프되게 한다(알람 임계 안정성).
 */
@Component
public class MicrometerLoanMetricsAdapter implements LoanMetricsPort {

    private final Counter advanceRequested;
    private final Counter advanceDisbursed;
    private final Counter advanceRejected;
    private final Counter corporateRequested;
    private final Counter corporateRejected;
    private final Counter corporateDisbursed;
    private final Counter repaymentApplied;
    private final Counter repaymentAmount;

    public MicrometerLoanMetricsAdapter(MeterRegistry registry) {
        this.advanceRequested = Counter.builder("loan.advance.requested")
                .description("선정산 대출 신청 성공 건수").register(registry);
        this.advanceDisbursed = Counter.builder("loan.advance.disbursed")
                .description("선정산 대출 실행 성공 건수").register(registry);
        this.advanceRejected = Counter.builder("loan.advance.rejected")
                .description("실행 시점 담보부족 승인 거절 건수").register(registry);
        this.corporateRequested = Counter.builder("loan.corporate.requested")
                .description("기업 신용대출 신청 성공 건수").register(registry);
        this.corporateRejected = Counter.builder("loan.corporate.rejected")
                .description("기업 신용평가 거절 건수").register(registry);
        this.corporateDisbursed = Counter.builder("loan.corporate.disbursed")
                .description("기업 신용대출 실행 성공 건수").register(registry);
        this.repaymentApplied = Counter.builder("loan.repayment.applied")
                .description("상환 차감 적용 건수").register(registry);
        this.repaymentAmount = Counter.builder("loan.repayment.amount")
                .baseUnit("KRW")
                .description("상환 차감 적용 금액 합계").register(registry);
    }

    @Override
    public void advanceRequested() {
        advanceRequested.increment();
    }

    @Override
    public void advanceDisbursed() {
        advanceDisbursed.increment();
    }

    @Override
    public void advanceRejected() {
        advanceRejected.increment();
    }

    @Override
    public void corporateRequested() {
        corporateRequested.increment();
    }

    @Override
    public void corporateRejected() {
        corporateRejected.increment();
    }

    @Override
    public void corporateDisbursed() {
        corporateDisbursed.increment();
    }

    @Override
    public void repaymentApplied(BigDecimal deductedAmount) {
        repaymentApplied.increment();
        if (deductedAmount != null && deductedAmount.signum() > 0) {
            repaymentAmount.increment(deductedAmount.doubleValue());
        }
    }
}
