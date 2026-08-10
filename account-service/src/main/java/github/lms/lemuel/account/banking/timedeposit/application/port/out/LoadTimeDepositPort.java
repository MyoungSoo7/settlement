package github.lms.lemuel.account.banking.timedeposit.application.port.out;

import github.lms.lemuel.account.banking.timedeposit.domain.TimeDeposit;

import java.util.List;
import java.util.Optional;

/** 정기예금 계좌 조회 아웃바운드 포트. */
public interface LoadTimeDepositPort {

    Optional<TimeDeposit> findById(Long depositId);

    /** 예금주별 계좌 목록 (최신 개설 순). */
    List<TimeDeposit> findByDepositorId(String depositorId);
}
