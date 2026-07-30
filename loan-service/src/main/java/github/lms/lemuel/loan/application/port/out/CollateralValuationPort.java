package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.CollateralType;

import java.math.BigDecimal;

/**
 * 담보 평가액 조달 아웃바운드 포트.
 *
 * <p>Phase 1 은 신청 시 입력값을 그대로 스냅샷으로 돌려주는 구현체였고, Phase 2 실연동에서
 * market-service(금융자산 시가)·common-data-service(주택 실거래가) 어댑터로 교체됐다 — 도메인은
 * 수정되지 않았다. loan-service 가 KRX·국토부를 직접 조회하면 market/commondata 와 책임이
 * 중복되므로, 실연동은 반드시 위성 서비스 경유로 한다.
 *
 * <p><b>평가 조달 실패는 신청을 막지 않는다</b>(가용성 우선 — 기준금리 {@code BaseRatePort} 와 동일
 * 원칙): 위성 미기동·데이터 부재·조회 키 부재는 신청자가 제시한 {@code declaredValue} 로 폴백하고
 * 경고만 남긴다. 확정 평가액은 담보에 스냅샷으로 박히므로 사후에 흔들리지 않는다.
 */
public interface CollateralValuationPort {

    /** 담보 평가액을 산출한다 — 위성 조회 성공 시 실측값, 그 외 {@code declaredValue} 폴백. */
    BigDecimal appraise(ValuationClaim claim);

    /**
     * 평가 대상 담보의 주장(claim) — 신청자가 제시한 값과 위성 조회 키.
     *
     * @param type          담보 유형
     * @param description   담보물 표시(부동산 소재지, 종목명 등) — 사람용 감사 추적
     * @param declaredValue 신청자가 제시한 평가액(위성 조회 불가 시 폴백)
     * @param marketRef     위성 조회 키 — EQUITY: 종목코드 6자리 / REAL_ESTATE: 실거래 recordKey 접두어.
     *                      null·공백이면 위성 조회를 생략한다
     * @param quantity      수량(주식 수 등) — 금융자산 시가 평가(단가×수량)에만 쓴다
     */
    record ValuationClaim(CollateralType type, String description, BigDecimal declaredValue,
                          String marketRef, Long quantity) {

        /** 위성 조회 없이 제시값만으로 평가하는 청구(주택담보 조회 키 미제출·보증서·예금 등). */
        public static ValuationClaim declared(CollateralType type, String description, BigDecimal declaredValue) {
            return new ValuationClaim(type, description, declaredValue, null, null);
        }
    }
}
