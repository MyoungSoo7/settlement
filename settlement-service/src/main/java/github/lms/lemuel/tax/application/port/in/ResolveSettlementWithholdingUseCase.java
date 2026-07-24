package github.lms.lemuel.tax.application.port.in;

import github.lms.lemuel.tax.application.WithholdingResolution;

import java.math.BigDecimal;

/**
 * 정산 확정(payout 산정) 시점에 셀러의 원천징수액을 해석하는 유스케이스 — settlement 확정 배치
 * ({@code SettlementConfirmItemWriter})가 실제 지급액을 {@code immediate − offset − withholding} 으로
 * 산정하기 위해 호출한다(ADR 0029, 2026-07-24 정정 — HIGH #4 실지급 통합 봉합).
 */
public interface ResolveSettlementWithholdingUseCase {

    /**
     * @param sellerId  세무 프로필 조회 대상 셀러
     * @param netAmount 정산 순액(원천징수율 3.3% 의 산정 기준 — immediate 가 아니라 net 전체)
     */
    WithholdingResolution resolveForPayout(Long sellerId, BigDecimal netAmount);
}
