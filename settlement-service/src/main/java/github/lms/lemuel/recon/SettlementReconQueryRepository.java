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

    /** 한 페이지 상한 — 대사 배치가 하루치를 통째로 메모리에 올리지 않게 자른다. */
    private static final int MAX_PAGE = 2000;

    private final JdbcClient jdbc;

    public SettlementReconQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * <b>캡처 시각</b> 기준 정산 행을 payment_id 커서로 페이지네이션해 돌려준다.
     *
     * <p><b>왜 {@code settlements.created_at} 이 아니라 프로젝션의 {@code captured_at} 인가:</b>
     * {@code created_at} 은 컨슈머가 이벤트를 처리해 행을 넣은 시각이다. 자정 직후 처리되거나
     * 재처리(replay)되면 같은 결제가 order 쪽 기준일({@code payments.captured_at})과 다른 날로
     * 잡혀, 한쪽에서는 MISSING·다른 쪽에서는 EXTRA 로 이중 계상된다. 대사가 없애야 할 거짓
     * 불일치를 대사가 만들어내는 셈이다. 이벤트의 실제 캡처 시각은 {@code settlement_payment_view}
     * 가 이미 들고 있으므로 그것으로 자른다.
     *
     * <p><b>왜 커서 페이지네이션인가:</b> 단일 limit 로 자르면 하루 정산이 상한을 넘는 순간
     * 초과분이 조용히 사라지고, 상대편(order)은 전건을 돌려주므로 그 차이가 전부 EXTRA 로
     * 보고된다. 호출자가 소진할 때까지 페이지를 돌 수 있어야 절단이 침묵하지 않는다.
     *
     * @param afterPaymentId 이 값보다 큰 payment_id 부터 (첫 페이지는 0)
     */
    public List<SettlementReconRow> listByCapturedDate(LocalDate date, long afterPaymentId, int limit) {
        return jdbc.sql("""
                        select s.payment_id,
                               (s.payment_amount - coalesce(s.refunded_amount, 0)) as net_paid_amount,
                               case when coalesce(s.refunded_amount, 0) > 0 then 'REFUNDED' else 'PAID' end as status
                          from settlements s
                          join settlement_payment_view v on v.payment_id = s.payment_id
                         where v.captured_at >= :dayStart
                           and v.captured_at < :dayEnd
                           and s.payment_id > :afterPaymentId
                         order by s.payment_id
                         limit :limit
                        """)
                .param("dayStart", date.atStartOfDay())
                .param("dayEnd", date.plusDays(1).atStartOfDay())
                .param("afterPaymentId", afterPaymentId)
                .param("limit", Math.min(Math.max(limit, 1), MAX_PAGE))
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
