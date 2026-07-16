package github.lms.lemuel.investment.domain;

import github.lms.lemuel.common.money.Money;
import github.lms.lemuel.investment.domain.exception.InvalidInvestmentOrderStateException;
import github.lms.lemuel.investment.domain.exception.InvestmentInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 투자 주문 애그리거트 루트 — 순수 POJO(프레임워크 의존 0).
 *
 * <p>상태 전이 불변식을 도메인 내부에서 강제한다(비정상 전이 → {@link InvalidInvestmentOrderStateException}).
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
    /** JPA @Version 낙관적 락 카운터. 신규 주문은 null(영속 시 0 초기화), 재구성 시 DB 값 보존. */
    private final Long version;

    private InvestmentOrder(Long id, Long sellerId, String stockCode, BigDecimal amount,
                            int scoreAtOrder, String gradeAtOrder, InvestmentOrderStatus status,
                            LocalDateTime createdAt, Long version) {
        this.id = id;
        this.sellerId = sellerId;
        this.stockCode = stockCode;
        this.amount = amount;
        this.scoreAtOrder = scoreAtOrder;
        this.gradeAtOrder = gradeAtOrder;
        this.status = status;
        this.createdAt = createdAt;
        this.version = version;
    }

    /**
     * 신규 투자 주문(REQUESTED, 주문 시각 = 도메인 내부 {@code LocalDateTime.now()}, 구 경로/테스트 호환).
     * 신규 코드는 KST Clock 기준 시각을 넘기는
     * {@link #request(Long, String, BigDecimal, int, String, LocalDateTime)} 오버로드를 쓴다.
     */
    public static InvestmentOrder request(Long sellerId, String stockCode, BigDecimal amount,
                                          int scoreAtOrder, String gradeAtOrder) {
        return request(sellerId, stockCode, amount, scoreAtOrder, gradeAtOrder, LocalDateTime.now());
    }

    /**
     * 신규 투자 주문(REQUESTED). {@code createdAt} 은 응용 계층이 KST {@link java.time.Clock} 으로 만든
     * 주문 시각 — 도메인은 시각을 생성하지 않고 받는다(off-by-one 방지, 테스트 결정성).
     */
    public static InvestmentOrder request(Long sellerId, String stockCode, BigDecimal amount,
                                          int scoreAtOrder, String gradeAtOrder, LocalDateTime createdAt) {
        if (createdAt == null) {
            throw new InvestmentInvariantViolationException("주문 시각(createdAt)은 필수입니다");
        }
        if (sellerId == null) {
            throw new InvestmentInvariantViolationException("sellerId 는 필수입니다");
        }
        if (stockCode == null || !stockCode.matches("\\d{6}")) {
            throw new InvestmentInvariantViolationException("stockCode 는 6자리 숫자여야 합니다: " + stockCode);
        }
        // 통화 금액 규칙(scale 2 HALF_UP·양수)은 Money VO 로 위임한다(SellerFunding 선례 동형).
        // 양수 불변식 판정 + 저장 표현을 모두 정규화된 값으로 통일한다 — funding 판정·저장·응답 기준이
        // scale 2 HALF_UP 로 일치하도록(소수 3자리 이상 입력이 도메인에 도달해도 여기서 흡수된다).
        if (amount == null || Money.of(amount).isZeroOrNegative()) {
            throw new InvestmentInvariantViolationException("투자 금액은 양수여야 합니다: " + amount, amount);
        }
        BigDecimal normalizedAmount = Money.of(amount).toBigDecimal();
        return new InvestmentOrder(null, sellerId, stockCode, normalizedAmount, scoreAtOrder, gradeAtOrder,
                InvestmentOrderStatus.REQUESTED, createdAt, null);
    }

    /** 영속 상태 재구성(리포지토리 전용). */
    public static InvestmentOrder reconstitute(Long id, Long sellerId, String stockCode, BigDecimal amount,
                                               int scoreAtOrder, String gradeAtOrder,
                                               InvestmentOrderStatus status, LocalDateTime createdAt) {
        return reconstitute(id, sellerId, stockCode, amount, scoreAtOrder, gradeAtOrder, status, createdAt, null);
    }

    /** 영속 상태 재구성(낙관적 락 version 포함 — 리포지토리 전용). */
    public static InvestmentOrder reconstitute(Long id, Long sellerId, String stockCode, BigDecimal amount,
                                               int scoreAtOrder, String gradeAtOrder,
                                               InvestmentOrderStatus status, LocalDateTime createdAt, Long version) {
        return new InvestmentOrder(id, sellerId, stockCode, amount, scoreAtOrder, gradeAtOrder,
                status, createdAt, version);
    }

    public void approve() {
        requireTransition(InvestmentOrderStatus.APPROVED);
        this.status = InvestmentOrderStatus.APPROVED;
    }

    public void execute() {
        requireTransition(InvestmentOrderStatus.EXECUTED);
        this.status = InvestmentOrderStatus.EXECUTED;
    }

    public void reject() {
        requireTransition(InvestmentOrderStatus.REJECTED);
        this.status = InvestmentOrderStatus.REJECTED;
    }

    public void cancel() {
        requireTransition(InvestmentOrderStatus.CANCELED);
        this.status = InvestmentOrderStatus.CANCELED;
    }

    // 상태 전이 가드 — 허용 전이는 InvestmentOrderStatus#canTransitionTo 단일 출처에 위임한다.
    private void requireTransition(InvestmentOrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidInvestmentOrderStateException(status, target);
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
    public Long getVersion() { return version; }
}
