package github.lms.lemuel.account.banking.savings.application.port.out;

import github.lms.lemuel.account.banking.savings.domain.InstallmentSavings;

/** 적금 계약 저장 포트. */
public interface SaveInstallmentSavingsPort {

    /**
     * 신규(id null)면 INSERT, 기존이면 UPDATE. 회차는 append-only 로 신규분만 추가된다.
     *
     * @return 영속 id 가 채워진 계약 (신규 개설 직후 GL 자연키에 쓸 savingsId 를 얻는 유일한 경로)
     */
    InstallmentSavings save(InstallmentSavings savings);
}
