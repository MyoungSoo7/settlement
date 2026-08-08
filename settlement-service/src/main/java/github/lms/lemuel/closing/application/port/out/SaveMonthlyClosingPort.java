package github.lms.lemuel.closing.application.port.out;

import github.lms.lemuel.closing.domain.MonthlyClosingRun;
import github.lms.lemuel.closing.domain.SellerMonthlyClosing;

import java.time.YearMonth;
import java.util.List;

/** 월마감 영속 — 마트 교체와 run 기록. */
public interface SaveMonthlyClosingPort {

    /**
     * COMPLETED run + 마트 행을 <b>한 트랜잭션</b>으로 적재한다.
     * 기간의 기존 마트 행은 전부 삭제 후 재삽입(재실행 멱등), run 은 기간 유니크 upsert.
     */
    MonthlyClosingRun saveCompleted(MonthlyClosingRun run, List<SellerMonthlyClosing> rows);

    /** run 단독 기록 — FAILED 감사 기록 경로(마트는 건드리지 않는다). */
    MonthlyClosingRun saveRun(MonthlyClosingRun run);
}
