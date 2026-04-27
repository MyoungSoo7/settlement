package github.lms.lemuel.settlement.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * 판매자 정산 주기.
 *
 * <p>각 값은 결제 발생일({@code paymentDate}) 이 주어졌을 때 해당 결제가 집계되어
 * 판매자에게 지급될 <b>정산일</b>을 계산하는 규칙을 포함한다.
 */
public enum SettlementCycle {
    /** 매일 정산 — paymentDate + 1일. 레거시 기본. */
    DAILY {
        @Override
        public LocalDate resolveSettlementDate(LocalDate paymentDate) {
            return paymentDate.plusDays(1);
        }
    },
    /** 매주 월요일 정산 — 결제 이후 첫 월요일. */
    WEEKLY_MON {
        @Override
        public LocalDate resolveSettlementDate(LocalDate paymentDate) {
            LocalDate nextMonday = paymentDate.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            return nextMonday;
        }
    },
    /** 매월 말일 정산 — 결제 월의 마지막 날. */
    MONTHLY_LAST {
        @Override
        public LocalDate resolveSettlementDate(LocalDate paymentDate) {
            return paymentDate.with(TemporalAdjusters.lastDayOfMonth());
        }
    };

    public abstract LocalDate resolveSettlementDate(LocalDate paymentDate);

    public static SettlementCycle fromStringOrDefault(String value) {
        if (value == null || value.isBlank()) return DAILY;
        try {
            return SettlementCycle.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DAILY;
        }
    }
}
