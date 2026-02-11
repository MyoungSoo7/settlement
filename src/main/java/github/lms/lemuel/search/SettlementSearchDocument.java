package github.lms.lemuel.search;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 정산 검색용 통합 Document
 * Settlement, Order, Payment, Refund 데이터를 통합하여 Elasticsearch에 저장
 */
@Document(indexName = "settlement_search", createIndex = false)
@Setting(settingPath = "elasticsearch/settlement-index-settings.json")
public class SettlementSearchDocument {

    @Id
    private String id; // settlement_id를 String으로 변환

    // Settlement 정보
    @Field(type = FieldType.Long)
    private Long settlementId;

    @Field(type = FieldType.Keyword)
    private String settlementStatus; // PENDING, CONFIRMED, CANCELED

    @Field(type = FieldType.Scaled_Float, scalingFactor = 100)
    private BigDecimal settlementAmount;

    @Field(type = FieldType.Date)
    private LocalDate settlementDate;

    @Field(type = FieldType.Date)
    private LocalDateTime settlementConfirmedAt;

    // Order 정보
    @Field(type = FieldType.Long)
    private Long orderId;

    @Field(type = FieldType.Long)
    private Long userId;

    @Field(type = FieldType.Keyword)
    private String orderStatus; // CREATED, PAID, CANCELED, REFUNDED

    @Field(type = FieldType.Scaled_Float, scalingFactor = 100)
    private BigDecimal orderAmount;

    @Field(type = FieldType.Date)
    private LocalDateTime orderCreatedAt;

    // Payment 정보
    @Field(type = FieldType.Long)
    private Long paymentId;

    @Field(type = FieldType.Keyword)
    private String paymentStatus; // READY, AUTHORIZED, CAPTURED, FAILED, CANCELED, REFUNDED

    @Field(type = FieldType.Scaled_Float, scalingFactor = 100)
    private BigDecimal paymentAmount;

    @Field(type = FieldType.Scaled_Float, scalingFactor = 100)
    private BigDecimal refundedAmount;

    @Field(type = FieldType.Text, analyzer = "nori_analyzer")
    private String paymentMethod; // 결제 수단 (한글 검색 가능)

    @Field(type = FieldType.Keyword)
    private String pgTransactionId; // PG사 거래 ID

    @Field(type = FieldType.Date)
    private LocalDateTime paymentCapturedAt;

    // Refund 정보 (여러 건의 환불을 취합)
    @Field(type = FieldType.Boolean)
    private Boolean hasRefund; // 환불 존재 여부

    @Field(type = FieldType.Integer)
    private Integer refundCount; // 환불 건수

    @Field(type = FieldType.Keyword)
    private String latestRefundStatus; // 가장 최근 환불 상태

    @Field(type = FieldType.Text, analyzer = "nori_analyzer")
    private String refundReason; // 환불 사유 (Nori 형태소 분석)

    @Field(type = FieldType.Date)
    private LocalDateTime latestRefundRequestedAt;

    @Field(type = FieldType.Date)
    private LocalDateTime latestRefundCompletedAt;

    // 검색 및 색인 시간
    @Field(type = FieldType.Date)
    private LocalDateTime indexedAt;

    // Constructors
    public SettlementSearchDocument() {
        this.indexedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getSettlementId() {
        return settlementId;
    }

    public void setSettlementId(Long settlementId) {
        this.settlementId = settlementId;
        this.id = settlementId != null ? settlementId.toString() : null;
    }

    public String getSettlementStatus() {
        return settlementStatus;
    }

    public void setSettlementStatus(String settlementStatus) {
        this.settlementStatus = settlementStatus;
    }

    public BigDecimal getSettlementAmount() {
        return settlementAmount;
    }

    public void setSettlementAmount(BigDecimal settlementAmount) {
        this.settlementAmount = settlementAmount;
    }

    public LocalDate getSettlementDate() {
        return settlementDate;
    }

    public void setSettlementDate(LocalDate settlementDate) {
        this.settlementDate = settlementDate;
    }

    public LocalDateTime getSettlementConfirmedAt() {
        return settlementConfirmedAt;
    }

    public void setSettlementConfirmedAt(LocalDateTime settlementConfirmedAt) {
        this.settlementConfirmedAt = settlementConfirmedAt;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(String orderStatus) {
        this.orderStatus = orderStatus;
    }

    public BigDecimal getOrderAmount() {
        return orderAmount;
    }

    public void setOrderAmount(BigDecimal orderAmount) {
        this.orderAmount = orderAmount;
    }

    public LocalDateTime getOrderCreatedAt() {
        return orderCreatedAt;
    }

    public void setOrderCreatedAt(LocalDateTime orderCreatedAt) {
        this.orderCreatedAt = orderCreatedAt;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(Long paymentId) {
        this.paymentId = paymentId;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(String paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public BigDecimal getPaymentAmount() {
        return paymentAmount;
    }

    public void setPaymentAmount(BigDecimal paymentAmount) {
        this.paymentAmount = paymentAmount;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public void setRefundedAmount(BigDecimal refundedAmount) {
        this.refundedAmount = refundedAmount;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public String getPgTransactionId() {
        return pgTransactionId;
    }

    public void setPgTransactionId(String pgTransactionId) {
        this.pgTransactionId = pgTransactionId;
    }

    public LocalDateTime getPaymentCapturedAt() {
        return paymentCapturedAt;
    }

    public void setPaymentCapturedAt(LocalDateTime paymentCapturedAt) {
        this.paymentCapturedAt = paymentCapturedAt;
    }

    public Boolean getHasRefund() {
        return hasRefund;
    }

    public void setHasRefund(Boolean hasRefund) {
        this.hasRefund = hasRefund;
    }

    public Integer getRefundCount() {
        return refundCount;
    }

    public void setRefundCount(Integer refundCount) {
        this.refundCount = refundCount;
    }

    public String getLatestRefundStatus() {
        return latestRefundStatus;
    }

    public void setLatestRefundStatus(String latestRefundStatus) {
        this.latestRefundStatus = latestRefundStatus;
    }

    public String getRefundReason() {
        return refundReason;
    }

    public void setRefundReason(String refundReason) {
        this.refundReason = refundReason;
    }

    public LocalDateTime getLatestRefundRequestedAt() {
        return latestRefundRequestedAt;
    }

    public void setLatestRefundRequestedAt(LocalDateTime latestRefundRequestedAt) {
        this.latestRefundRequestedAt = latestRefundRequestedAt;
    }

    public LocalDateTime getLatestRefundCompletedAt() {
        return latestRefundCompletedAt;
    }

    public void setLatestRefundCompletedAt(LocalDateTime latestRefundCompletedAt) {
        this.latestRefundCompletedAt = latestRefundCompletedAt;
    }

    public LocalDateTime getIndexedAt() {
        return indexedAt;
    }

    public void setIndexedAt(LocalDateTime indexedAt) {
        this.indexedAt = indexedAt;
    }
}
