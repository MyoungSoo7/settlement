package github.lms.lemuel.investment.domain;

/**
 * 초보 투자 체크 — kakaopay-invest-companion 플러그인의 규칙 스크리닝을 서비스로 이식한 집계 뷰.
 *
 * <ul>
 *   <li><b>score</b>: 투자점수(회계 축 — R1 수익성·R2 성장 상당, financial 공개 API)</li>
 *   <li><b>newsRisk</b>: 악재 뉴스 체크(R3 — company 공개 API)</li>
 *   <li><b>pricePosition</b>: 시세 위치 체크(R4 추격·R5 고점 — market 공개 API)</li>
 *   <li><b>macro</b>: 거시 컨텍스트(economics 공개 API)</li>
 *   <li><b>tradePlan</b>: 매매계획 규칙 — 시세 축이 없으면 null</li>
 * </ul>
 *
 * <p>점수 축이 앵커(회계자료 없으면 체크 자체가 404)이고, 위성 축(뉴스·시세·거시)은
 * 원천 장애 시 해당 축만 UNAVAILABLE 로 강등된다 — 전체 실패가 아니다.
 */
public record BeginnerInvestmentCheck(String stockCode, InvestmentScore score,
                                      NewsRiskCheck newsRisk, PricePositionCheck pricePosition,
                                      MacroCheck macro, TradePlan tradePlan) {
}
