package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.application.port.in.RegisterCommissionRatePolicyUseCase.RegisterPolicyCommand;
import github.lms.lemuel.settlement.domain.CommissionRatePolicy;

/**
 * 요율 정책 저장 — 행 UPDATE 없이 신규 등록과 조기 종료(close)만 있다.
 *
 * <p>사유·작성자는 정책과 분리될 수 없는 감사 정보라 커맨드째 넘긴다.
 */
public interface SaveCommissionRatePolicyPort {

    CommissionRatePolicy save(RegisterPolicyCommand command);

    /** 조기 종료 — 요율 변경은 close + 신규 행으로 한다(이력 보존). */
    void close(Long policyId);
}
