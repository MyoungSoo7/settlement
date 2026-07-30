package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.CollateralRevaluation;
import github.lms.lemuel.loan.domain.MarginCall;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 담보 재평가 이력 · 마진콜 아웃바운드 포트.
 *
 * <p>두 개념을 한 포트에 둔 이유: 재평가는 그 자체로 조회 대상이 아니라 <b>마진콜 판정의 입력</b>이라
 * 항상 함께 쓰인다. 포트를 쪼개면 서비스가 두 포트를 늘 짝으로 주입받게 되어 응집도만 떨어진다.
 */
public interface CollateralRiskPort {

    /** 재평가 이력 1건 추가(평가액 덮어쓰기 아님). */
    CollateralRevaluation appendRevaluation(CollateralRevaluation revaluation);

    /**
     * 담보의 최신 평가액 — 재평가 이력이 있으면 그 값, 없으면 설정 시점 평가액.
     * 마진콜 판정은 이 값을 기준으로 한다.
     */
    Optional<BigDecimal> findLatestValue(Long collateralId);

    /** 대출의 활성(OPEN) 마진콜 — 중복 발생 방지 선체크. */
    Optional<MarginCall> findOpenMarginCall(Long loanId);

    MarginCall saveMarginCall(MarginCall marginCall);
}
