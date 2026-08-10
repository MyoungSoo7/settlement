package github.lms.lemuel.account.banking.timedeposit.application.port.out;

import github.lms.lemuel.account.banking.timedeposit.domain.TimeDeposit;

/**
 * 정기예금 계좌 영속 아웃바운드 포트.
 *
 * <p>저장 결과를 <b>반환</b>하는 이유는 신규 개설 시 채번된 id 가 GL 전표의 자연키
 * ({@code refId = TD-{depositId}}) 를 이루기 때문이다 — id 없이 전기하면 멱등키가 성립하지 않는다.
 */
public interface SaveTimeDepositPort {

    TimeDeposit save(TimeDeposit deposit);
}
