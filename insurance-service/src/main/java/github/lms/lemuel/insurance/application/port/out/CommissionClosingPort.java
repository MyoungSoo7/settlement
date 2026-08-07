package github.lms.lemuel.insurance.application.port.out;

import github.lms.lemuel.insurance.domain.CommissionClosing;

import java.time.YearMonth;
import java.util.List;

/**
 * 월 수수료 마감 스냅샷 조회·저장 포트.
 *
 * <p>append-only — 갱신·삭제 메서드는 의도적으로 없다. (fc, 월) 유일성은
 * {@code uq_commission_closing_fc_month} 가 최후 방어한다.
 */
public interface CommissionClosingPort {

    /** 해당 (fc, 월) 마감이 이미 존재하는가 — 배치 재실행 멱등 스킵용. */
    boolean existsByFcAndMonth(String fcId, YearMonth month);

    /** 해당 월의 마감 스냅샷 전부. */
    List<CommissionClosing> findByMonth(YearMonth month);

    /** 마감 스냅샷 신규 저장 (INSERT 전용). */
    CommissionClosing save(CommissionClosing closing);
}
