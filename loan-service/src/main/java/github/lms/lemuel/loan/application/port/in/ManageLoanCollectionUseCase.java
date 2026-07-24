package github.lms.lemuel.loan.application.port.in;

import github.lms.lemuel.loan.domain.LoanAdvance;

/**
 * 선정산 대출 회수(collection) 관리 인바운드 포트 — 연체 진입·상각(대손 확정).
 *
 * <p>운영자(ADMIN) 수동 조작 진입점이다. 실행일/만기일 컬럼 기반의 시간 트리거 자동화는
 * 스키마 확장이 선행돼야 하므로(후속 과제), 현재는 회수 담당자가 명시적으로 상태를 전이시킨다.
 * 상태 전이·불변식은 도메인 {@link LoanAdvance} 가 강제한다.
 */
public interface ManageLoanCollectionUseCase {

    /** 연체 진입: DISBURSED 대출을 OVERDUE 로 전이한다(미상환잔액이 남아 있어야 성립). */
    LoanAdvance markOverdue(Long loanId);

    /**
     * 상각(회수 불능 확정): OVERDUE 대출을 WRITTEN_OFF 로 전이하고, 미상환잔액을 대손
     * (BAD_DEBT_EXPENSE/BAD_DEBT_ALLOWANCE) 전표로 인식한다.
     */
    LoanAdvance writeOff(Long loanId);
}
