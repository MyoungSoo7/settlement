package github.lms.lemuel.insurance.application.port.out;

import github.lms.lemuel.insurance.domain.BancaRuleEvaluator.BankInsurerPremium;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 방카 판매 실적 집계 포트 — 25%룰 모니터링 입력.
 */
public interface LoadBancaSalesPort {

    /**
     * [from, toExclusive) 에 효력이 개시된 BANCA 신계약 보험료를 (은행, 부문, 원수사)로 합산한다.
     *
     * <p>원수사(insurer_code) 또는 부문(insurer_sector) 미지정 상품의 계약은 집계에서
     * 제외된다 — 카탈로그 정비 대상이지 룰 위반 판정 대상이 아니다.
     */
    List<BankInsurerPremium> aggregateBancaPremiums(LocalDate fromInclusive, LocalDate toExclusive);

    /**
     * 방카 파트너 은행 자산총액 레지스트리 전량 — 은행 코드 → 자산총액(원).
     *
     * <p>25%룰 적용 대상(자산 2조 이상) 판정 입력. 미등록 은행은 맵에 없다 —
     * 판정은 fail-closed(적용 대상 취급)로 도메인이 처리한다.
     */
    Map<String, BigDecimal> loadPartnerBankAssets();
}
