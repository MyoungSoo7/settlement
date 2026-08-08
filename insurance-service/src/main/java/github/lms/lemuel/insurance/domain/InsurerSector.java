package github.lms.lemuel.insurance.domain;

/**
 * 원수사 부문 — 생명보험/손해보험.
 *
 * <p>방카 25%룰의 판매비중은 <b>부문별로 각각</b> 계산한다(V8) —
 * 생보 상품 비중의 분모는 그 은행의 생보 신계약 총액이지, 방카 전체 총액이 아니다.
 */
public enum InsurerSector {

    /** 생명보험. */
    LIFE,

    /** 손해보험. */
    NON_LIFE
}
