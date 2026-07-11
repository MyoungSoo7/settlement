package github.lms.lemuel.investment.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 투자 주문 애그리거트 루트 — 순수 POJO(프레임워크 의존 0).
 *
 * <p>상태 전이 불변식을 도메인 내부에서 강제한다(비정상 전이 → {@link IllegalStateException}).
 * 신청 시점의 투자점수·등급(scoreAtOrder/gradeAtOrder)을 스냅샷으로 보존한다 — 이후 재무제표 갱신으로
 * 점수가 바뀌어도 주문 이력은 신청 당시 근거를 유지한다(정산 commission_rate 스냅샷과 동일 철학).
 */
public class InvestmentOrder {

    private Long id;
    private final Long sellerId;
    private final String stockCode;
    private final BigDecimal amount;
    private final int scoreAtOrder;
    private final String gradeAtOrder;
    private InvestmentOrderStatus status;
    private final LocalDateTime createdAt;

    private InvestmentOrder(Long id, Long sellerId, String stockCode, BigDecimal amount,
                            int scoreAtOrder, String gradeAtOrder, InvestmentOrderStatus status,
                            LocalDateTime createdAt) {
        this.id = id;
        this.sellerId = sellerId;
        this.stockCode = stockCode;
        this.amount = amount;
        this.scoreAtOrder = scoreAtOrder;
        this.gradeAtOrder = gradeAtOrder;
        this.status = status;
        this.createdAt = createdAt;
    }

    /** 신규 투자 주문(REQUESTED). */
    public static InvestmentOrder request(Long sellerId, String stockCode, BigDecimal amount,
                                          int scoreAtOrder, String gradeAtOrder) {
        if (sellerId == null) {
            throw new IllegalArgumentException("sellerId 는 필수입니다");
        }
        if (stockCode == null || !stockCode.matches("\\d{6}")) {
            throw new IllegalArgumentException("stockCode 는 6자리 숫자여야 합니다: " + stockCode);
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("투자 금액은 양수여야 합니다: " + amount);
        }
        return new InvestmentOrder(null, sellerId, stockCode, amount, scoreAtOrder, gradeAtOrder,
                InvestmentOrderStatus.REQUESTED, LocalDateTime.now());
    }

    /** 영속 상태 재구성(리포지토리 전용). */
    public static InvestmentOrder reconstitute(Long id, Long sellerId, String stockCode, BigDecimal amount,
                                               int scoreAtOrder, String gradeAtOrder,
                                               InvestmentOrderStatus status, LocalDateTime createdAt) {
        return new InvestmentOrder(id, sellerId, stockCode, amount, scoreAtOrder, gradeAtOrder, status, createdAt);
    }

    public void approve() {
        requireStatus(InvestmentOrderStatus.REQUESTED, "승인");
        this.status = InvestmentOrderStatus.APPROVED;
    }

    public void execute() {
        requireStatus(InvestmentOrderStatus.APPROVED, "집행");
        this.status = InvestmentOrderStatus.EXECUTED;
    }

    public void reject() {
        requireStatus(InvestmentOrderStatus.REQUESTED, "거절");
        this.status = InvestmentOrderStatus.REJECTED;
    }

    public void cancel() {
        if (status != InvestmentOrderStatus.REQUESTED && status != InvestmentOrderStatus.APPROVED) {
            throw new IllegalStateException("취소는 REQUESTED 또는 APPROVED 에서만 가능합니다. 현재=" + status);
        }
        this.status = InvestmentOrderStatus.CANCELED;
    }

    private void requireStatus(InvestmentOrderStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException(
                    action + "은(는) " + expected + " 상태에서만 가능합니다. 현재=" + status);
        }
    }

    public Long getId() { return id; }
    public Long getSellerId() { return sellerId; }
    public String getStockCode() { return stockCode; }
    public BigDecimal getAmount() { return amount; }
    public int getScoreAtOrder() { return scoreAtOrder; }
    public String getGradeAtOrder() { return gradeAtOrder; }
    public InvestmentOrderStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
