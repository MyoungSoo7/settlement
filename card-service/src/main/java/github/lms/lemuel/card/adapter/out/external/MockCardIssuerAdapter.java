package github.lms.lemuel.card.adapter.out.external;

import github.lms.lemuel.card.application.port.out.CardIssuerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 카드 채번 어댑터의 <b>스텁 구현</b> — 실제 카드사/발급 대행 연동 전까지 마스킹된 표시용 번호만
 * 만들어 돌려준다. 이름에 Mock 을 박아 둔 것은 의도다: 이 클래스가 프로덕션에 살아 있는 한
 * "발급된 카드로 결제가 되지 않는다"가 정상 동작이며, 그 사실이 코드에서 바로 읽혀야 한다.
 *
 * <p>번호를 <b>처음부터 마스킹된 형태로만</b> 만든다 — 원본 PAN 을 생성했다가 마스킹하는 구조면
 * 그 중간값이 로그·힙덤프·예외 메시지에 남을 수 있다. 여기서는 존재한 적이 없는 값은 샐 수도 없다.
 *
 * <p>실 발급사를 붙일 때 이 클래스를 지우고 같은 포트를 구현하면 유스케이스는 변경되지 않는다.
 *
 * <p><b>fail-closed</b>: prod 프로파일에서는 등록되지 않는다. 기동 WARN 은 사람이 로그를 봐야만
 * 보이고 아무것도 막지 못했다 — 실 발급사가 없는 채로 운영에 뜨면 "발급은 성공했는데 결제가 되지
 * 않는 카드"가 정상 발급된 것처럼 남는다. 실 구현을 붙이기 전까지 {@code CardIssuerPort} 빈 부재로
 * {@code IssueCardService} 주입이 실패해 운영 기동이 막히는 것이 의도된 동작이다.
 */
@Component
@Profile("!prod")
public class MockCardIssuerAdapter implements CardIssuerPort {

    private static final Logger log = LoggerFactory.getLogger(MockCardIssuerAdapter.class);

    /** 앞 12자리는 아예 만들지 않는다 — 마스크 문자열 뒤에 표시용 4자리만 붙인다. */
    private static final String MASK_PREFIX = "****-****-****-";

    private static final int LAST4_BOUND = 10_000;

    public MockCardIssuerAdapter() {
        log.warn("[MockCardIssuer] 실 발급사 연동이 없는 스텁 채번기가 활성화됐습니다 — "
                + "발급된 카드번호는 표시용이며 결제에 사용할 수 없습니다.");
    }

    @Override
    public IssuedCard issue(Long cardAccountId, Long holderUserId) {
        String last4 = String.format("%04d", ThreadLocalRandom.current().nextInt(LAST4_BOUND));
        log.info("[MockCardIssuer] 채번 accountId={} holderUserId={} last4={}",
                cardAccountId, holderUserId, last4);
        return new IssuedCard(MASK_PREFIX + last4);
    }
}
