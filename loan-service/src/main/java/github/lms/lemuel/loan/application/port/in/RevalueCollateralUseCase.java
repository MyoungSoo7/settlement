package github.lms.lemuel.loan.application.port.in;

import java.math.BigDecimal;

/**
 * 담보 재평가 · 마진콜 판정 인바운드 포트.
 *
 * <p>재평가와 판정을 한 유스케이스로 묶은 이유: 재평가 값을 기록만 하고 판정을 나중에 하면 그 사이에
 * 담보 부족 상태가 방치된다. 시가를 새로 안 시점이 곧 조치를 결정해야 하는 시점이다.
 */
public interface RevalueCollateralUseCase {

    /** 판정 결과 — 조치 없음 / 마진콜 발생 / 강제처분 이관. */
    enum Outcome {
        /** 담보유지비율 충족 — 조치 없음. 기존 활성 마진콜이 있으면 해소된다. */
        SUFFICIENT,
        /** 유지비율(140%) 미달 — 추가담보 요구. */
        MARGIN_CALL,
        /** 청산선(120%) 미달 — 강제처분 이관(연체·기한이익상실 경로로 넘긴다). */
        LIQUIDATION
    }

    /**
     * @param requiredAmount 마진콜 발생 시 추가담보 요구액, 그 외 0
     */
    record RevaluationResult(Long loanId, Long collateralId, BigDecimal revaluedValue,
                             BigDecimal coverageRatio, Outcome outcome, BigDecimal requiredAmount) {
    }

    /**
     * 담보를 재평가하고 담보유지비율을 판정한다.
     *
     * @param source 평가 출처(MARKET_SERVICE / COMMON_DATA_SERVICE / MANUAL)
     */
    RevaluationResult revalue(Long loanId, BigDecimal revaluedValue, String source);
}
