package github.lms.lemuel.insurance.application.port.out;

import github.lms.lemuel.insurance.domain.BancaRuleEvaluator.BankInsurerPremium;

import java.time.LocalDate;
import java.util.List;

/**
 * 방카 판매 실적 집계 포트 — 25%룰 모니터링 입력.
 */
public interface LoadBancaSalesPort {

    /**
     * [from, toExclusive) 에 효력이 개시된 BANCA 신계약 보험료를 (은행, 원수사)로 합산한다.
     *
     * <p>원수사 미지정(insurer_code NULL) 상품의 계약은 집계에서 제외된다 —
     * 카탈로그 정비 대상이지 룰 위반 판정 대상이 아니다.
     */
    List<BankInsurerPremium> aggregateBancaPremiums(LocalDate fromInclusive, LocalDate toExclusive);
}
