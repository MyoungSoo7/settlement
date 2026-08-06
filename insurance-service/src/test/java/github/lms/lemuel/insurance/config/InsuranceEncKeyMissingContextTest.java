package github.lms.lemuel.insurance.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INSURANCE_ENC_KEY <b>미설정</b> 시 ApplicationContext 초기화가 실패하고, 그 예외(또는 cause chain)
 * 메시지에 {@code INSURANCE_ENC_KEY} 라는 환경변수 이름이 문자 그대로 들어있는지 검증한다.
 *
 * <p>settlement-service 의 {@code PAYOUT_ENC_KEY} 를 주입하지 않고 배포해 원인불명 CrashLoop 이 난
 * 실제 사고가 있었다. 로그 어디에도 키 이름이 없어 원인 규명이 늦어졌다. 그래서 여기서 확인하는 것은
 * "부팅이 실패한다"가 아니라 <b>운영자가 로그 한 줄만 보고 무엇을 주입해야 하는지 알 수 있다</b>는 점이다.
 *
 * <p>테스트 JVM 에는 루트 {@code build.gradle.kts} 의 {@code tasks.withType<Test>} env 블록이
 * INSURANCE_ENC_KEY 를 주입해 둔다. 따라서 "키가 없는 상태"를 만들려면 컨텍스트 환경에서
 * {@code systemEnvironment} 프로퍼티 소스를 걷어내야 한다 — 그것이 곧 키를 주입하지 않은 파드의 상태다.
 *
 * <p>주입 경로는 운영과 동일하게 맞춘다: application.yml 의 {@code app.insurance.enc-key} 원문 값을
 * 그대로 읽어 프로퍼티로 심고({@link #encKeyRawValueFromApplicationYml()}), Spring Boot 와 같이
 * {@link PropertySourcesPlaceholderConfigurer}(미해결 플레이스홀더에 strict)로 해석시킨다.
 * yml 에 기본값({@code ${INSURANCE_ENC_KEY:...}})이 생기면 원문 검증에서 먼저 걸린다.
 *
 * <p>키 형식 오류(Base64 아님·길이 불일치)의 진단 메시지는 {@code InsuranceEncKeyFailFastTest} 가 담당한다.
 */
class InsuranceEncKeyMissingContextTest {

    /** 환경변수 이름 자체가 진단 단서다 — 메시지에서 이 문자열이 사라지면 사고가 재발한다. */
    private static final String ENV_NAME = "INSURANCE_ENC_KEY";

    /** application.yml 의 {@code enc-key:} 한 줄에서 원문 값을 뽑는다. */
    private static final Pattern ENC_KEY_LINE = Pattern.compile("(?m)^\\s*enc-key:\\s*(\\S+)\\s*$");

    /** 테스트용 32바이트 키 — decode 시 "test-only-ins-enc-key-0123456789". */
    private static final String VALID_KEY = "dGVzdC1vbmx5LWlucy1lbmMta2V5LTAxMjM0NTY3ODk=";

    /**
     * 운영과 동일한 플레이스홀더 해석기. Spring Boot 는 {@code PropertyPlaceholderAutoConfiguration}
     * 으로 이 빈을 등록하며, 미해결 플레이스홀더에 대해 예외를 던진다(무시하지 않는다).
     */
    @Configuration(proxyBeanMethods = false)
    static class PlaceholderResolution {
        @Bean
        static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }

    /** 키를 주입하지 않은 파드와 동일한 상태 — 환경변수 프로퍼티 소스를 통째로 걷어낸다. */
    private final ApplicationContextRunner runnerWithoutEnv = new ApplicationContextRunner()
            .withInitializer(context -> {
                ConfigurableEnvironment environment = context.getEnvironment();
                environment.getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            })
            .withUserConfiguration(PlaceholderResolution.class, InsuranceEncConfig.class);

    @Test
    @DisplayName("application.yml 의 enc-key 는 기본값 없는 ${INSURANCE_ENC_KEY} 다 — 기본값이 생기면 미설정이 조용히 통과한다")
    void applicationYmlDeclaresEnvPlaceholderWithoutDefault() {
        assertThat(encKeyRawValueFromApplicationYml())
                .as("yaml 기본값을 두면 키 미설정이 부팅 실패로 드러나지 않는다")
                .isEqualTo("${" + ENV_NAME + "}");
    }

    @Test
    @DisplayName("키 미설정 — 컨텍스트 초기화가 실패하고, cause chain 메시지에 INSURANCE_ENC_KEY 가 등장한다")
    void contextFailsToStartWithDiagnosableMessageWhenKeyMissing() {
        // 여기서 심는 값은 application.yml 과 같은 형태의 리터럴이다. yml 원문과의 일치는
        // applicationYmlDeclaresEnvPlaceholderWithoutDefault() 가 따로 지킨다 — 이 테스트는
        // "키가 없을 때 무슨 일이 벌어지는가"만 결정적으로 검증한다.
        runnerWithoutEnv
                .withPropertyValues("app.insurance.enc-key=${" + ENV_NAME + "}")
                .run(context -> {
                    assertThat(context).hasFailed();

                    List<String> messages = causeChainMessages(context.getStartupFailure());
                    assertThat(messages)
                            .as("기동 실패 메시지 어디에도 키 이름이 없으면 운영자는 원인을 못 찾는다: %s", messages)
                            .anyMatch(message -> message.contains(ENV_NAME));

                    // 실패 사유까지 못 박는다. 키 이름만 보면 "형식이 틀렸다"(잘못된 기본값 탓)와
                    // "아예 없다"를 구분할 수 없어, yml 에 기본값이 생겨도 테스트가 통과해 버린다.
                    assertThat(messages)
                            .as("미해결 플레이스홀더가 아닌 다른 이유로 실패했다 — 키 미설정 경로가 아니다: %s", messages)
                            .anyMatch(message -> message.contains(ENV_NAME)
                                    && message.toLowerCase().contains("placeholder"));
                });
    }

    @Test
    @DisplayName("같은 배선에 키만 주입하면 컨텍스트가 뜬다 — 실패 원인이 키 부재임을 확정한다")
    void contextStartsWhenKeyIsProvided() {
        runnerWithoutEnv
                .withPropertyValues("app.insurance.enc-key=" + VALID_KEY)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(InsuranceEncConfig.class);
                });
    }

    // ────────────────────────────────────────────────────────────────────

    /** 예외 자신부터 최말단 cause 까지의 메시지를 모은다 (null 메시지는 건너뛴다). */
    private static List<String> causeChainMessages(Throwable failure) {
        assertThat(failure).as("컨텍스트가 실패했는데 예외가 없다").isNotNull();

        List<String> messages = new ArrayList<>();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.add(current.getMessage());
            }
            if (current.getCause() == current) {
                break; // 자기참조 방어
            }
        }
        return messages;
    }

    /** application.yml 에서 {@code app.insurance.enc-key} 의 원문 값을 읽는다. */
    private static String encKeyRawValueFromApplicationYml() {
        String yml;
        try {
            yml = new String(new ClassPathResource("application.yml").getContentAsByteArray(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("insurance-service application.yml 을 읽을 수 없다", e);
        }

        Matcher matcher = ENC_KEY_LINE.matcher(yml);
        assertThat(matcher.find())
                .as("application.yml 에 app.insurance.enc-key 선언이 사라졌다")
                .isTrue();
        return matcher.group(1);
    }
}
