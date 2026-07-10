package github.lms.lemuel.loan.domain;

import java.math.BigDecimal;

/**
 * 기업 신용평가 입력 — financial-statements-service 의 회사 식별 + 최신 요약 재무제표 파생지표를
 * loan-service 경계로 옮겨온 순수 값 객체(MSA 코드 의존 0).
 *
 * <p>파생지표(부채비율·영업이익률·ROA)는 financial 이 %로 계산한 값이며 결측이면 null 이다.
 * null 은 {@code CorporateCreditPolicy} 에서 자본잠식/결측으로 취급해 해당 축 점수를 0 으로 처리한다.
 *
 * @param stockCode        6자리 종목코드
 * @param corpName         회사명
 * @param market           시장 구분(KOSPI/KOSDAQ 등, financial 원문 문자열)
 * @param fiscalYear       회계연도(최신 재무제표 기준, 재무 없으면 null)
 * @param debtRatio        부채비율(%) — 안정성 점수 산정
 * @param operatingMargin  영업이익률(%) — 수익성 점수 산정
 * @param roa              총자산이익률(%) — 수익성 점수 산정
 * @param totalEquity      자본총계 — 한도 산정
 * @param netIncome        당기순이익 — 참고(음수 여부)
 */
public record CorporateFinancials(
        String stockCode,
        String corpName,
        String market,
        Integer fiscalYear,
        BigDecimal debtRatio,
        BigDecimal operatingMargin,
        BigDecimal roa,
        BigDecimal totalEquity,
        BigDecimal netIncome) {
}
