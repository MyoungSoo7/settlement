package github.lms.lemuel.account.banking.savings.application.port.out;

import github.lms.lemuel.account.banking.savings.domain.InstallmentSavings;

import java.util.List;
import java.util.Optional;

/** 적금 계약 조회 포트 — 회차까지 함께 채워진 애그리거트를 돌려준다. */
public interface LoadInstallmentSavingsPort {

    Optional<InstallmentSavings> findById(Long savingsId);

    /** 예금주의 전체 계약 (개설일 내림차순). */
    List<InstallmentSavings> findByDepositorId(String depositorId);
}
