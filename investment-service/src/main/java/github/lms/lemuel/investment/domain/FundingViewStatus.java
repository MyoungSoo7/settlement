package github.lms.lemuel.investment.domain;

/**
 * 재원 프로젝션 상태. investment 는 settlement 의 확정(confirmed) 정산금만 재원으로 인식하므로
 * 단일 상태만 존재한다(향후 취소/차감 반영 시 확장 여지).
 */
public enum FundingViewStatus {
    CONFIRMED
}
