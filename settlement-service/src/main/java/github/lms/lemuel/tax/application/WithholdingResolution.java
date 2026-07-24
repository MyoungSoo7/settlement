package github.lms.lemuel.tax.application;

import github.lms.lemuel.tax.domain.TaxType;

import java.math.BigDecimal;

/**
 * 정산 확정(payout 산정) 시점의 원천징수 해석 결과.
 *
 * <p><b>미등록 셀러 정책(2026-07-24 명시, ADR 0029 §D)</b>: 세무 프로필이 없으면 개인/사업자 여부를
 * 판단할 근거가 없다. 이 경우 <b>사업자로 취급해 원천징수를 강제하지 않는다</b>(withholdingAmount=0,
 * 전액 지급) — 이유:
 * <ol>
 *   <li>세무 프로필 레지스트리는 이 Seed 에서 신설된 것이라, 기존/신규 셀러 절대다수가 미등록 상태다.
 *       미등록을 "확정 보류"로 처리하면 세무 프로필을 등록하지 않은 모든 셀러의 자동 지급이 전면 중단돼
 *       기존 정산 파이프라인을 광범위하게 깨뜨린다(하위호환성 붕괴).</li>
 *   <li>반대로 미등록 셀러를 "개인(원천징수 대상)"으로 보수적으로 가정해 3.3% 를 강제로 공제하면, 실제로는
 *       사업자인 셀러의 돈을 플랫폼이 착오로 과다공제하는 더 심각한 자금 사고가 된다.</li>
 * </ol>
 * 따라서 이 정책은 "원천징수 의무 이행"보다 "임의로 셀러 자금을 잘못 공제하지 않는 것"을 우선한다 — 세무
 * 리스크(과소원천징수)는 플랫폼이 감수하고, 관리자가 세무 프로필을 등록하는 즉시(다음 정산부터) 정상
 * 원천징수가 적용된다. {@link #profileRegistered()} 가 false 인 모든 케이스는 호출자가 WARN 로그로
 * 드러내 감사·후속 조치를 유도해야 한다.
 */
public record WithholdingResolution(boolean profileRegistered, TaxType taxType, BigDecimal withholdingAmount) {

    public static WithholdingResolution unregistered() {
        return new WithholdingResolution(false, null, BigDecimal.ZERO);
    }

    public static WithholdingResolution of(TaxType taxType, BigDecimal withholdingAmount) {
        return new WithholdingResolution(true, taxType, withholdingAmount);
    }

    public boolean hasWithholding() {
        return withholdingAmount.signum() > 0;
    }
}
