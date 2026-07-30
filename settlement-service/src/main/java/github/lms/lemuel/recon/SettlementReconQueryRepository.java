package github.lms.lemuel.recon;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * settlement 가 자기 소유 데이터(settlement_db)의 대사용 행을 노출하기 위한 read-only 조회.
 *
 * <p>order 쪽 {@code InternalReconController} 와 대칭이다 — 양측 모두 자기 DB 만 읽고 HTTP 로
 * 숫자를 주고받아, 대사를 위해 cross-DB 연결을 만들지 않는다.
 */
@Repository
public class SettlementReconQueryRepository {

    /** 한 번에 내보내는 행 상한 — 대사 배치가 메모리를 통째로 먹지 않게 자른다. */
    private static final int MAX_ROWS = 5000;

    private final JdbcClient jdbc;

    public SettlementReconQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * <b>캡처일</b> 기준 정산 행. 대사 상대인 order 의 {@code /internal/recon/captured-payments} 가
     * {@code captured_at::date} 로 자르므로 이쪽도 정산 생성 시각({@code created_at})으로 맞춘다.
     *
     * <p>{@code settlement_date} 로 자르면 안 된다 — 그건 지급 예정일(T+1)이라 같은 결제가 하루
     * 밀린 채로 비교돼 전 건이 어긋난 것처럼 보인다.
     *
     * <p>금액은 {@code payment_amount - refunded_amount}, 상태는 환불 반영 여부만 PAID/REFUNDED 로
     * 정규화한다. 정산 자체의 라이프사이클 상태(PENDING/DONE 등)는 결제 원천에 대응물이 없어
     * 그대로 비교하면 상시 STATUS_MISMATCH 가 된다.
     */
    public List<SettlementReconRow> listByCapturedDate(LocalDate date, int limit) {
        return jdbc.sql("""
                        select payment_id,
                               (payment_amount - coalesce(refunded_amount, 0)) as net_paid_amount,
                               case when coalesce(refunded_amount, 0) > 0 then 'REFUNDED' else 'PAID' end as status
                          from settlements
                         where created_at::date = :date
                         order by payment_id
                         limit :limit
                        """)
                .param("date", date)
                .param("limit", Math.min(Math.max(limit, 1), MAX_ROWS))
                .query((rs, rowNum) -> new SettlementReconRow(
                        rs.getLong("payment_id"),
                        rs.getBigDecimal("net_paid_amount"),
                        rs.getString("status")))
                .list();
    }

    /** 대사용 정산 행 (PII 없음 — 결제 키·금액·상태만). */
    public record SettlementReconRow(Long paymentId, BigDecimal netPaidAmount, String status) {
    }
}
