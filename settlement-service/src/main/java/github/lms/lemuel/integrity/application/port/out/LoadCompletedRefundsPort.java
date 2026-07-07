package github.lms.lemuel.integrity.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * order 의 완료 환불 목록을 얻는 아웃바운드 포트 (INV-8) — 내부 대사 API
 * {@code /internal/recon/refunds-completed} 프록시. 양측 모두 자기 DB 만 읽는다 (cross-DB 0).
 */
public interface LoadCompletedRefundsPort {

    List<CompletedRefund> refundsCompleted(LocalDate from, LocalDate to, int limit);

    record CompletedRefund(Long refundId, BigDecimal amount, LocalDate completedDate) {
    }
}
