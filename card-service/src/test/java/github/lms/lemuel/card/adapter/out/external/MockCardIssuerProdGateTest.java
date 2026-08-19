package github.lms.lemuel.card.adapter.out.external;

import github.lms.lemuel.card.application.port.out.CardIssuerPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스텁 카드 채번기가 <b>운영에서 뜨지 않음</b>을 못박는다.
 *
 * <p>{@link MockCardIssuerAdapter} 는 표시용 마스킹 번호만 만드는 스텁이라, 이게 운영에 뜨면
 * "발급은 성공했는데 결제가 안 되는 카드"가 정상 발급된 것처럼 DB 에 남는다. 기동 시 WARN 을
 * 찍고는 있었지만 로그는 아무것도 막지 못한다.
 *
 * <p>실 발급사 어댑터가 없는 현재, prod 프로파일에서는 {@code CardIssuerPort} 빈이 없어
 * {@code IssueCardService} 생성자 주입이 실패한다 — 실 연동을 붙이기 전까지 운영 기동이 막히는
 * 것이 의도된 동작이다(가짜 카드를 발급하느니 뜨지 않는 편이 낫다).
 */
class MockCardIssuerProdGateTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MockCardIssuerAdapter.class);

    @Test
    @DisplayName("prod 프로파일에서는 스텁 카드 채번기가 등록되지 않는다")
    void stubIsNotRegistered_inProdProfile() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(CardIssuerPort.class));
    }

    @Test
    @DisplayName("비운영에서는 스텁 카드 채번기가 등록된다 (로컬·시연 경로 보존)")
    void stubIsRegistered_outsideProd() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(MockCardIssuerAdapter.class));
    }
}
