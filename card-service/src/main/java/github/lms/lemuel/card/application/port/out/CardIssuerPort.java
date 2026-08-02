package github.lms.lemuel.card.application.port.out;

/**
 * 카드 채번(발급) 포트 — 실제 카드사/발급 대행에 카드번호를 요청하는 경계.
 *
 * <p>돌려주는 값이 <b>마스킹된 번호뿐</b>인 것이 의도다. 원본 PAN 은 이 포트를 넘어오지 않으므로
 * 카드 도메인·DB·로그 어디에도 남지 않는다(PCI 스코프 축소). 원본이 필요한 결제 승인은
 * 발급사 토큰으로 처리하는 별도 경계의 몫이고, Phase 1 범위 밖이다.
 *
 * <p>채번은 <b>부작용이 있는 외부 호출</b>이라 호출 순서가 곧 안전이다 — 인가·멤버십·중복·한도
 * 검증을 모두 통과한 뒤에만 불린다. 검증 전에 채번하면 거절된 요청마다 발급사에 실물 번호가
 * 하나씩 태워지고, 그 번호는 우리 DB 에 남지 않아 회수할 방법이 없다.
 */
public interface CardIssuerPort {

    IssuedCard issue(Long cardAccountId, Long holderUserId);

    /** 발급 결과 — 마스킹된 카드번호만 담는다. */
    record IssuedCard(String maskedCardNo) {
    }
}
