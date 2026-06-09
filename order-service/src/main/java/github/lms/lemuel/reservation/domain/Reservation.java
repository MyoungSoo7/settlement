package github.lms.lemuel.reservation.domain;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 시공 예약 Domain Entity (순수 POJO, 스프링/JPA 의존성 없음)
 *
 * 업체 회원이 등록하는 마루 시공 예약. 상태머신 전이는 비즈니스 메서드로만 수행한다.
 * 면적 단위는 평, 금액 단위는 원(KRW).
 */
@Getter
@Setter
public class Reservation {

    private Long id;
    private Long companyId;                 // 예약 등록 업체 회원(User.id, role=COMPANY)
    private ReservationStatus status;

    // 시공 일정 / 현장 정보
    private LocalDate scheduledDate;
    private String siteAddress;
    private String sitePassword;
    private String siteManagerName;
    private String siteManagerPhone;

    // 제품 정보 (관리자 등록 제품 선택 + 예약 시점 스냅샷)
    private Long productId;
    private String woodSpecies;
    private String brand;
    private String productName;
    private String productSize;

    // 시공 정보
    private BigDecimal constructionArea;
    private boolean fieldMeasured;
    private boolean expansion;
    private BigDecimal expansionArea = BigDecimal.ZERO;
    private boolean newFloor;

    // 부자재 정보
    private boolean baseboard;
    private boolean protectionWork;
    private BigDecimal protectionArea = BigDecimal.ZERO;

    // 자동 계산 결과 (pricing 엔진 산출값)
    private BigDecimal protectionFee = BigDecimal.ZERO;
    private BigDecimal additionalFee = BigDecimal.ZERO;

    private String note;
    private String canceledReason;

    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Reservation() {
        this.status = ReservationStatus.REQUESTED;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 예약 등록 — 필수 입력 검증 후 REQUESTED 상태로 생성.
     */
    public static Reservation register(Long companyId, LocalDate scheduledDate,
                                       String siteAddress, String siteManagerName,
                                       String siteManagerPhone, BigDecimal constructionArea) {
        Reservation r = new Reservation();
        r.companyId = companyId;
        r.scheduledDate = scheduledDate;
        r.siteAddress = siteAddress;
        r.siteManagerName = siteManagerName;
        r.siteManagerPhone = siteManagerPhone;
        r.constructionArea = constructionArea;
        r.validate();
        return r;
    }

    public void validate() {
        if (companyId == null) {
            throw new IllegalArgumentException("companyId is required");
        }
        if (scheduledDate == null) {
            throw new IllegalArgumentException("scheduledDate is required");
        }
        if (siteAddress == null || siteAddress.isBlank()) {
            throw new IllegalArgumentException("siteAddress is required");
        }
        if (siteManagerPhone == null || siteManagerPhone.isBlank()) {
            throw new IllegalArgumentException("siteManagerPhone is required");
        }
        if (constructionArea == null || constructionArea.signum() <= 0) {
            throw new IllegalArgumentException("constructionArea must be positive");
        }
        if (expansion && (expansionArea == null || expansionArea.signum() <= 0)) {
            throw new IllegalArgumentException("expansionArea must be positive when expansion is true");
        }
        if (protectionWork && (protectionArea == null || protectionArea.signum() <= 0)) {
            throw new IllegalArgumentException("protectionArea must be positive when protectionWork is true");
        }
    }

    // ── 상태 전이 (가드 포함) ────────────────────────────────

    public void confirm() {
        requireStatus(ReservationStatus.REQUESTED);
        transitionTo(ReservationStatus.CONFIRMED);
    }

    public void assign() {
        requireStatus(ReservationStatus.CONFIRMED);
        transitionTo(ReservationStatus.ASSIGNED);
    }

    public void start() {
        requireStatus(ReservationStatus.ASSIGNED);
        transitionTo(ReservationStatus.IN_PROGRESS);
    }

    public void complete() {
        requireStatus(ReservationStatus.IN_PROGRESS);
        transitionTo(ReservationStatus.COMPLETED);
    }

    public void cancel(String reason) {
        if (status.isTerminal()) {
            throw new IllegalStateException("Cannot cancel a terminal reservation: " + status);
        }
        this.canceledReason = reason;
        transitionTo(ReservationStatus.CANCELED);
    }

    /**
     * pricing 엔진이 계산한 보양비/추가비용 반영.
     */
    public void applyCalculatedFees(BigDecimal protectionFee, BigDecimal additionalFee) {
        this.protectionFee = protectionFee != null ? protectionFee : BigDecimal.ZERO;
        this.additionalFee = additionalFee != null ? additionalFee : BigDecimal.ZERO;
        this.updatedAt = LocalDateTime.now();
    }

    private void requireStatus(ReservationStatus expected) {
        if (this.status != expected) {
            throw new IllegalStateException(
                    "Invalid transition: expected " + expected + " but was " + this.status);
        }
    }

    private void transitionTo(ReservationStatus next) {
        this.status = next;
        this.updatedAt = LocalDateTime.now();
    }
}
