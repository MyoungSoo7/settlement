package github.lms.lemuel.point.domain;

import github.lms.lemuel.point.domain.exception.InsufficientPointException;
import github.lms.lemuel.point.domain.exception.InvalidPointStateException;
import github.lms.lemuel.point.domain.exception.PointInvariantViolationException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 구매자 포인트 계정 — 잔고의 단일 진실원.
 *
 * <p>불변식 {@code total = available + locked} 와 3필드 음수 금지를 {@link #enforceInvariant()} 가
 * 모든 상태 변경 직후 재검증한다(DB CHECK 는 최후 방어선). {@code deposit_accounts} 의
 * {@code SellerDepositAccount} 와 같은 2중 방어 구조다.
 *
 * <p><b>잔고 요약과 로트 상세의 정합</b>({@code available == Σ ACTIVE lot.remaining - locked})은
 * 여러 애그리거트에 걸친 불변식이라 이 클래스 혼자 검증할 수 없다 — 응용 서비스가 로트 소비 계획과
 * 함께 원자적으로 갱신하고, 정기 대사가 사후 검증한다.
 *
 * <p>{@code locked} 는 Phase 2(입금대기 결제의 포인트 선점)까지 항상 0 이다. 컬럼과 불변식을
 * 처음부터 두는 이유는, 나중에 추가하면 CHECK 제약을 갈아엎어야 하기 때문이다.
 */
public class PointAccount {

    private Long id;
    private final Long userId;
    private BigDecimal available;
    private BigDecimal locked;
    private BigDecimal total;
    private PointAccountStatus status;
    private long version;
    private final OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    private PointAccount(Long id, Long userId, BigDecimal available, BigDecimal locked,
                         BigDecimal total, PointAccountStatus status, long version,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this.id = id;
        this.userId = userId;
        this.available = available;
        this.locked = locked;
        this.total = total;
        this.status = status;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 신규 계정 개설 — 잔고 0, ACTIVE. */
    public static PointAccount open(Long userId) {
        if (userId == null) {
            throw new InvalidPointStateException("userId 없이 포인트 계정을 열 수 없습니다", "NONE", "open");
        }
        OffsetDateTime now = OffsetDateTime.now();
        PointAccount account = new PointAccount(null, userId, zero(), zero(), zero(),
                PointAccountStatus.ACTIVE, 0L, now, now);
        account.enforceInvariant();
        return account;
    }

    /**
     * 영속 상태로부터 복원. 복원 시점에도 불변식을 검증한다 — 저장소가 깨진 행을 돌려주면
     * 그 사실을 조용히 흡수하지 않고 즉시 드러내야 한다.
     */
    public static PointAccount rehydrate(Long id, Long userId, BigDecimal available, BigDecimal locked,
                                         BigDecimal total, PointAccountStatus status, long version,
                                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        PointAccount account = new PointAccount(id, userId, normalize(available), normalize(locked),
                normalize(total), status, version, createdAt, updatedAt);
        account.enforceInvariant();
        return account;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 잔고 변경 — 모든 경로가 requirePoint → 상태검사 → 갱신 → enforceInvariant 순서를 지킨다
    // ──────────────────────────────────────────────────────────────────────────

    /** 적립·충전. 정지 계정도 받는다(조사 중이라고 정상 주문의 적립을 뺏지 않는다). */
    public void grant(BigDecimal amount) {
        BigDecimal value = requirePoint(amount, "grant");
        requireGrantable("grant");
        this.available = this.available.add(value);
        this.total = this.total.add(value);
        touch();
        enforceInvariant();
    }

    /** 결제 사용(차감). ACTIVE 계정에서만 가능하고, 잔액을 넘으면 거절한다. */
    public void use(BigDecimal amount) {
        BigDecimal value = requirePoint(amount, "use");
        if (!status.allowsUse()) {
            throw new InvalidPointStateException(
                    "사용할 수 없는 계정 상태입니다: " + status, status.name(), "use");
        }
        if (available.compareTo(value) < 0) {
            throw new InsufficientPointException(
                    "포인트 잔액 부족: 요청 " + value + ", 가용 " + available, value, available);
        }
        this.available = this.available.subtract(value);
        this.total = this.total.subtract(value);
        touch();
        enforceInvariant();
    }

    /** 환불 복원. 고객 재산을 돌려주는 경로라 정지 계정에도 허용한다. */
    public void restore(BigDecimal amount) {
        BigDecimal value = requirePoint(amount, "restore");
        requireGrantable("restore");
        this.available = this.available.add(value);
        this.total = this.total.add(value);
        touch();
        enforceInvariant();
    }

    /**
     * 유효기간 소멸 차감. 소멸 대상은 배치가 로트에서 계산해 넘기므로 잔액을 넘을 수 없다 —
     * 넘는다면 잔고와 로트가 어긋났다는 뜻이라 {@link PointInvariantViolationException} 이다.
     */
    public void expire(BigDecimal amount) {
        forfeit(amount, "expire", "소멸액");
    }

    /**
     * 적립 취소 차감 — 주문이 취소·환불되면 그 주문으로 준 적립분을 회수한다.
     *
     * <p>{@link #expire} 와 잔고 효과는 같지만 원장 엔트리 유형과 GL 상대계정이 다르다
     * (소멸은 이익 인식, 취소는 판촉비 환입). 그래서 메서드를 나눠 호출 지점이 의도를 드러내게 한다.
     */
    public void revoke(BigDecimal amount) {
        forfeit(amount, "revoke", "회수액");
    }

    /**
     * 잔고에서 되가져오는 공통 경로. 차감액은 언제나 <b>로트에서 계산되어</b> 넘어오므로 잔액을
     * 넘을 수 없다 — 넘는다면 잔고와 로트가 어긋났다는 뜻이라 사용자 입력 오류가 아니라 로직 버그다.
     */
    private void forfeit(BigDecimal amount, String operation, String label) {
        BigDecimal value = requirePoint(amount, operation);
        if (available.compareTo(value) < 0) {
            throw new PointInvariantViolationException(
                    "불변식 위반: " + label + "(" + value + ")이 가용 잔고(" + available
                            + ")를 초과 — 잔고와 로트가 어긋났다");
        }
        this.available = this.available.subtract(value);
        this.total = this.total.subtract(value);
        touch();
        enforceInvariant();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 상태 전이 — 동일 상태 재적용은 멱등 no-op
    // ──────────────────────────────────────────────────────────────────────────

    /** 사용 정지. 적립·복원은 계속 받는다. */
    public void suspend() {
        if (status == PointAccountStatus.SUSPENDED) {
            return;
        }
        requireNotClosed("suspend");
        this.status = PointAccountStatus.SUSPENDED;
        touch();
    }

    /** 정지 해제. */
    public void activate() {
        if (status == PointAccountStatus.ACTIVE) {
            return;
        }
        requireNotClosed("activate");
        this.status = PointAccountStatus.ACTIVE;
        touch();
    }

    /** 해지 — 잔액이 0 일 때만. 잔액을 남긴 채 닫으면 고객 재산이 장부에서 사라진다. */
    public void close() {
        if (status == PointAccountStatus.CLOSED) {
            return;
        }
        if (total.signum() != 0) {
            throw new InvalidPointStateException(
                    "잔액이 남은 계정은 해지할 수 없습니다: total=" + total, status.name(), "close");
        }
        this.status = PointAccountStatus.CLOSED;
        touch();
    }

    public void assignId(Long id) {
        if (this.id != null) {
            throw new InvalidPointStateException("이미 ID 가 할당된 계정입니다: " + this.id,
                    String.valueOf(this.id), "assignId");
        }
        this.id = id;
    }

    /** 낙관적 락 버전 동기화 — 영속 어댑터가 저장 후 최신 버전을 되돌려 줄 때 쓴다. */
    public void syncVersion(long version) {
        this.version = version;
    }

    public boolean isUsable() {
        return status.allowsUse();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 내부 헬퍼
    // ──────────────────────────────────────────────────────────────────────────

    private static BigDecimal zero() {
        return PointAmounts.zero();
    }

    private static BigDecimal normalize(BigDecimal value) {
        return PointAmounts.normalize(value, "rehydrate");
    }

    private static BigDecimal requirePoint(BigDecimal amount, String operation) {
        return PointAmounts.requirePoint(amount, operation);
    }

    private void requireGrantable(String operation) {
        if (!status.allowsGrant()) {
            throw new InvalidPointStateException(
                    "적립할 수 없는 계정 상태입니다: " + status, status.name(), operation);
        }
    }

    private void requireNotClosed(String operation) {
        if (status == PointAccountStatus.CLOSED) {
            throw new InvalidPointStateException(
                    "해지된 계정은 상태를 되돌릴 수 없습니다", status.name(), operation);
        }
    }

    /**
     * 도메인 불변식 검증 — 모든 상태 변경 이후 호출된다.
     * 이 메서드가 예외를 던지면 로직 버그이므로, 절대 외부에서 catch 해서 넘어가면 안 된다.
     */
    private void enforceInvariant() {
        if (available.signum() < 0) {
            throw new PointInvariantViolationException("불변식 위반: available < 0 (" + available + ")");
        }
        if (locked.signum() < 0) {
            throw new PointInvariantViolationException("불변식 위반: locked < 0 (" + locked + ")");
        }
        if (total.signum() < 0) {
            throw new PointInvariantViolationException("불변식 위반: total < 0 (" + total + ")");
        }
        BigDecimal expectedTotal = available.add(locked);
        if (total.compareTo(expectedTotal) != 0) {
            throw new PointInvariantViolationException(
                    "불변식 위반: total(" + total + ") != available(" + available
                            + ") + locked(" + locked + ") = " + expectedTotal);
        }
    }

    private void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public BigDecimal getAvailable() { return available; }
    public BigDecimal getLocked() { return locked; }
    public BigDecimal getTotal() { return total; }
    public PointAccountStatus getStatus() { return status; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
