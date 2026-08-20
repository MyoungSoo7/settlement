package github.lms.lemuel.payout.adapter.out.firmbanking;

import github.lms.lemuel.payout.application.port.out.FirmBankingPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모의 펌뱅킹이 <b>운영에서 절대 뜨지 않음</b>을 못박는다.
 *
 * <p>이전 조건은 {@code matchIfMissing = true} 였다. 즉 {@code app.firmbanking.mode} 가 어떤 이유로든
 * 해석되지 않으면(프로퍼티 누락·오타·프로파일 미주입) 모의 어댑터가 <b>조용히</b> 붙어 지급 배치가
 * 돈을 보내지 않고도 성공을 반환했다. 실패는 소리를 내야 하고, 돈 경로에서 기본값은 "안전한 쪽"이어야
 * 한다 — 그래서 모의는 명시적으로 요청해야만 뜨고, prod 프로파일에서는 요청해도 뜨지 않는다.
 *
 * <p>실 어댑터({@code FepFirmBankingAdapter}, mode=fep)도 없으면 {@code FirmBankingPort} 빈이 아예
 * 없어 {@code PayoutSingleExecutor} 생성자 주입이 실패한다 — 기동 자체가 막히는 fail-closed 다.
 */
class MockFirmBankingProdGateTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MockFirmBankingAdapter.class);

    @Test
    @DisplayName("mode 미설정이면 모의 펌뱅킹이 등록되지 않는다 (조용한 기본값 제거)")
    void mockIsNotRegistered_whenModeUnset() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(FirmBankingPort.class));
    }

    @Test
    @DisplayName("prod 프로파일에서는 mode=mock 을 명시해도 모의 펌뱅킹이 등록되지 않는다")
    void mockIsNotRegistered_inProdProfile_evenWhenExplicitlyRequested() {
        runner.withPropertyValues("app.firmbanking.mode=mock")
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(FirmBankingPort.class));
    }

    @Test
    @DisplayName("비운영에서 mode=mock 을 명시하면 모의 펌뱅킹이 등록된다 (로컬·시연 경로 보존)")
    void mockIsRegistered_whenExplicitlyRequestedOutsideProd() {
        runner.withPropertyValues("app.firmbanking.mode=mock")
                .run(ctx -> assertThat(ctx).hasSingleBean(MockFirmBankingAdapter.class));
    }
}
