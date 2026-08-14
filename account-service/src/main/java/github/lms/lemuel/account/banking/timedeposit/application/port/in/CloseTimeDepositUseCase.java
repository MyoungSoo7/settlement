package github.lms.lemuel.account.banking.timedeposit.application.port.in;

import github.lms.lemuel.account.banking.timedeposit.domain.TimeDeposit;

/**
 * 정기예금 해지 인바운드 포트 — 만기해지·중도해지 두 경로.
 *
 * <p>해지일 역시 커맨드 인자가 아니라 서버 시계가 정한다(소급 해지로 이자를 부풀리는 경로 차단).
 * {@code depositorId} 는 JWT 주체에서만 파생되며, 계좌의 예금주와 다르면
 * {@code TimeDepositAccessDeniedException}(403) 이다.
 */
public interface CloseTimeDepositUseCase {

    /** 만기 해지 — 약정이율 적용. 만기 전 계좌면 거절하지 않고 중도해지를 쓰도록 유도하지도 않는다(호출자 선택). */
    TimeDeposit closeOnMaturity(String depositorId, Long depositId);

    /** 중도 해지 — 중도해지이율 적용, 실제 예치일수(ACT/365) 기준. */
    TimeDeposit closeEarly(String depositorId, Long depositId);
}
