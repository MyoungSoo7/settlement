package github.lms.lemuel.report.application.port.in;

import github.lms.lemuel.report.domain.BucketGranularity;
import github.lms.lemuel.report.domain.CashflowReport;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public interface GenerateCashflowReportUseCase {

    CashflowReport generate(CashflowReportCommand command);

    record CashflowReportCommand(
            LocalDate from,
            LocalDate to,
            BucketGranularity granularity,
            Long sellerId   // nullable — null 이면 시스템 전체, 값이 있으면 판매자 단위
    ) {

        private static final long MAX_DAYS = 366L;

        public CashflowReportCommand {
            if (from == null || to == null) {
                throw new IllegalArgumentException("from/to are required");
            }
            if (from.isAfter(to)) {
                throw new IllegalArgumentException("from must be <= to");
            }
            if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
                throw new IllegalArgumentException(
                        "period exceeds " + MAX_DAYS + " days (requested: " + ChronoUnit.DAYS.between(from, to) + ")");
            }
            if (granularity == null) {
                granularity = BucketGranularity.DAY;
            }
        }

        /** 시스템 전체 리포트용 편의 생성자 (sellerId=null). */
        public CashflowReportCommand(LocalDate from, LocalDate to, BucketGranularity granularity) {
            this(from, to, granularity, null);
        }

        /** 판매자 단위인지 판정. */
        public boolean isSellerScoped() {
            return sellerId != null;
        }
    }
}
