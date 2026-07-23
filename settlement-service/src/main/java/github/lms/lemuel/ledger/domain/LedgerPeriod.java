package github.lms.lemuel.ledger.domain;

import github.lms.lemuel.common.money.Money;
import github.lms.lemuel.ledger.domain.exception.InvalidLedgerPeriodStateException;
import github.lms.lemuel.ledger.domain.exception.LedgerInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * 원장 회계 기간(월) — 자체 원장의 월마감·기간잠금 단위(YYYY-MM).
 *
 * <p>한 기간은 {@code OPEN} 으로 시작(암묵적: 행이 없으면 OPEN 으로 간주)하고, 마감({@link #close})으로
 * {@code CLOSED} 가 되며 그 시점의 <b>확정 시산표 합계</b>(차변·대변)를 스냅샷으로 못박는다. CLOSED 는
 * 종결 상태이며 재개봉은 없다.
 *
 * <p>불변(Immutable) 원칙: 식별 필드({@code period}·{@code createdAt})는 {@code final} 로 봉인한다.
 * 상태 전이({@link #close})만 가변 필드(status·closedAt·closedBy·스냅샷 합계)를 1회 갱신한다. public setter 를
 * 두지 않으며, 영속 레코드 복원은 {@link #rehydrate} 팩토리로만 수행한다(도메인 순수 POJO).
 */
public class LedgerPeriod {

    // ── 불변 식별 필드 ────────────────────────────────────────────────
    private final YearMonth period;
    private final LocalDateTime createdAt;

    // ── 가변 필드 (PK 1회 부여·마감 전이 시에만 변경) ──────────────────
    private Long id;
    private LedgerPeriodStatus status;
    private LocalDateTime closedAt;
    private String closedBy;
    private BigDecimal totalDebit;
    private BigDecimal totalCredit;

    private LedgerPeriod(Long id, YearMonth period, LedgerPeriodStatus status,
                         LocalDateTime closedAt, String closedBy,
                         BigDecimal totalDebit, BigDecimal totalCredit, LocalDateTime createdAt) {
        this.id = id;
        this.period = period;
        this.status = status;
        this.closedAt = closedAt;
        this.closedBy = closedBy;
        this.totalDebit = totalDebit;
        this.totalCredit = totalCredit;
        this.createdAt = createdAt;
    }

    /** 신규 OPEN 기간 생성 — 마감 스냅샷은 아직 없다(null). */
    public static LedgerPeriod open(YearMonth period) {
        if (period == null) {
            throw new LedgerInvariantViolationException("period 필수");
        }
        return new LedgerPeriod(null, period, LedgerPeriodStatus.OPEN,
                null, null, null, null, LocalDateTime.now());
    }

    /** 영속 레코드 복원 전용(어댑터 toDomain). 저장된 상태를 그대로 재구성한다(검증 재실행 없음). */
    public static LedgerPeriod rehydrate(Long id, YearMonth period, LedgerPeriodStatus status,
                                         LocalDateTime closedAt, String closedBy,
                                         BigDecimal totalDebit, BigDecimal totalCredit,
                                         LocalDateTime createdAt) {
        return new LedgerPeriod(id, period, status, closedAt, closedBy, totalDebit, totalCredit, createdAt);
    }

    /**
     * 기간 마감 — OPEN → CLOSED 전이 + 확정 시산표 합계 스냅샷 못박기.
     *
     * @param closedBy    마감 실행 운영자(감사 추적)
     * @param totalDebit  마감 시점 확정 시산표 차변 합계(≥0)
     * @param totalCredit 마감 시점 확정 시산표 대변 합계(≥0)
     * @throws InvalidLedgerPeriodStateException 이미 CLOSED 인 기간을 재마감하려는 경우
     * @throws LedgerInvariantViolationException  스냅샷 합계가 null/음수인 경우
     */
    public void close(String closedBy, BigDecimal totalDebit, BigDecimal totalCredit) {
        if (!status.canTransitionTo(LedgerPeriodStatus.CLOSED)) {
            throw new InvalidLedgerPeriodStateException(status, LedgerPeriodStatus.CLOSED);
        }
        if (closedBy == null || closedBy.isBlank()) {
            throw new LedgerInvariantViolationException("closedBy 필수");
        }
        Money debit = requireNonNegative(totalDebit, "totalDebit");
        Money credit = requireNonNegative(totalCredit, "totalCredit");

        this.status = LedgerPeriodStatus.CLOSED;
        this.closedBy = closedBy;
        this.totalDebit = debit.toBigDecimal();
        this.totalCredit = credit.toBigDecimal();
        this.closedAt = LocalDateTime.now();
    }

    private static Money requireNonNegative(BigDecimal value, String field) {
        if (value == null) {
            throw new LedgerInvariantViolationException(field + " 필수");
        }
        Money money = Money.of(value);
        if (money.isNegative()) {
            throw new LedgerInvariantViolationException(field + " 는 음수일 수 없습니다: " + value);
        }
        return money;
    }

    /** 영속 후 DB PK 를 1회만 주입(write-once). */
    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1회만 부여할 수 있습니다");
        }
        this.id = id;
    }

    /** 주어진 일자가 이 기간(월)에 속하는지. */
    public boolean covers(LocalDate date) {
        return date != null && YearMonth.from(date).equals(period);
    }

    public boolean isClosed() {
        return status == LedgerPeriodStatus.CLOSED;
    }

    public boolean isOpen() {
        return status == LedgerPeriodStatus.OPEN;
    }

    // ========== Getters ==========

    public Long getId() {
        return id;
    }

    public YearMonth getPeriod() {
        return period;
    }

    /** 영속·표현 경계용 "YYYY-MM" 문자열. */
    public String getPeriodYm() {
        return period.toString();
    }

    public LedgerPeriodStatus getStatus() {
        return status;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public String getClosedBy() {
        return closedBy;
    }

    public BigDecimal getTotalDebit() {
        return totalDebit;
    }

    public BigDecimal getTotalCredit() {
        return totalCredit;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
