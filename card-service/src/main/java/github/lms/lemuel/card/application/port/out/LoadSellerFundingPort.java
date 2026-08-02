package github.lms.lemuel.card.application.port.out;

import java.math.BigDecimal;

/**
 * 셀러의 정산 재원(미지급 정산금·유보금) 조회 포트. 카드 한도 산정의 재원 축이다.
 *
 * <p>{@link SellerFunding} 은 account-service 의 동명 record 와 <b>이름만 같고 다른 타입</b>이다 —
 * 그쪽은 {@code sellerId} 필드를 포함하고 패키지도 다르다. 서비스 간 타입을 공유하지 않고
 * card 는 자기 포트 타입만 쓴다(어댑터가 JSON 계약에서 이 타입으로 옮긴다).
 */
public interface LoadSellerFundingPort {

    /**
     * <b>폴백하지 않는다</b> — 재원을 모르는 상태에서 추정 한도를 부여하면 그 자체가 여신 사고다.
     *
     * @throws FundingUnavailableException 재원을 확인하지 못한 모든 경우. 이 예외가 어댑터가 아니라
     *         <b>포트 옆</b>에 사는 이유는 실패 방식이 구현 세부가 아니라 포트 계약의 일부이고,
     *         유스케이스가 이를 잡아 번역해야 하는데 ArchUnit 이 application→adapter 의존을
     *         금지하기 때문이다(어댑터가 안쪽을 향해 의존하는 것은 허용).
     */
    SellerFunding load(String sellerId);

    record SellerFunding(BigDecimal sellerPayable, BigDecimal holdbackPayable) {
    }
}
