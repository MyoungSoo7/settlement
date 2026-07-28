package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.LoanAdvance;

import java.time.LocalDateTime;
import java.util.List;

public interface LoadLoanPort {

    /** ID 로 단건 조회. 없으면 IllegalArgumentException. */
    LoanAdvance load(Long loanId);

    /** 셀러 단위 조회 (조회 API 용). */
    List<LoanAdvance> findBySeller(Long sellerId);

    /**
     * 셀러의 미상환(DISBURSED·OVERDUE) 대출을 FIFO(오래된 순)로 비관적 락 조회 — 상환 차감용.
     * 연체(OVERDUE) 대출도 포함해 새 정산금으로 자동 회수한다(OVERDUE→REPAID). 동시 차감 경합을 직렬화한다.
     */
    List<LoanAdvance> findRepayableBySellerForUpdate(Long sellerId);

    /**
     * 만기 경과한 DISBURSED 대출의 <b>id</b>(자동 연체 대상). {@code dueAt < asOf} 이며 {@code dueAt} 이 NULL 인
     * 구(舊) 데이터는 제외한다 — 만기 미상 대출을 자동 연체로 오탐하지 않는다. 배치는 건별 트랜잭션에서 id 로
     * 락 재조회하므로 여기서는 애그리거트를 하이드레이트하지 않고 id 만 투영한다(핫패스 낭비 제거, 발견 #8).
     * {@code limit} 으로 1회 스캔량을 제한한다 — 대량 백로그가 힙을 채우지 않게(초과분은 다음 스캔에서 처리).
     */
    List<Long> findOverdueCandidateIds(LocalDateTime asOf, int limit);

    /**
     * 상각 임계를 넘긴 OVERDUE 대출의 <b>id</b>(자동 상각 대상). {@code dueAt < asOf}(asOf = now - writeOffDays),
     * {@code dueAt} NULL 제외. id 만 투영하고 {@code limit} 으로 1회 스캔량을 제한한다(발견 #8).
     */
    List<Long> findWriteOffCandidateIds(LocalDateTime asOf, int limit);
}
