package github.lms.lemuel.loan.application.port.in;

import java.math.BigDecimal;

/**
 * 담보 실행 인바운드 포트 — 기한이익상실(DEFAULTED) 이후 회수 절차.
 *
 * <p>두 경로가 담보 계열에 따라 갈린다: 부동산·금융자산은 <b>처분</b>해 매각대금으로 회수하고,
 * 보증부는 처분 대상이 없으므로 보증기관에 <b>대위변제</b>를 청구한다. 어느 쪽이든 회수 부족분은
 * 상각으로 종결된다.
 */
public interface EnforceCollateralUseCase {

    /**
     * @param recovered 채권에 충당된 회수액
     * @param surplus   채권을 초과해 회수된 금액(처분이익)
     * @param writtenOff 회수 부족으로 상각된 금액
     */
    record EnforcementResult(Long loanId, BigDecimal recovered, BigDecimal surplus,
                             BigDecimal writtenOff, String finalStatus) {
    }

    /**
     * 담보 처분. 매각대금으로 채권을 회수하고 부족분은 상각한다.
     *
     * @param proceeds 처분 매각대금(양수)
     */
    EnforcementResult dispose(Long loanId, BigDecimal proceeds);

    /**
     * 보증기관 대위변제 청구. 회수액은 정책의 보증비율(85%)만큼이며, 미보증분은 상각된다 —
     * 보증부라도 손실이 0 이 아닌 이유다.
     */
    EnforcementResult subrogate(Long loanId);
}
