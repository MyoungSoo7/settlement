package github.lms.lemuel.insurance.config;

import github.lms.lemuel.insurance.adapter.out.persistence.InsurancePiiEncryptionConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INSURANCE_ENC_KEY 미설정·오설정 시 <b>원인이 즉시 읽히는 기동 실패</b>가 나는지 검증한다.
 *
 * <p>settlement-service 의 {@code PAYOUT_ENC_KEY} 를 주입하지 않은 채 배포해 원인불명 CrashLoop 이
 * 발생한 실제 사고가 있었다. 그때 로그에는 키 이름이 어디에도 없어 원인 규명이 늦어졌다.
 * 그래서 이 테스트가 확인하는 것은 "예외가 난다"가 아니라 <b>예외 메시지에 {@code INSURANCE_ENC_KEY}
 * 라는 환경변수 이름이 문자 그대로 들어있다</b>는 점이다 — 운영자가 로그 한 줄만 보고 무엇을 주입해야
 * 하는지 알 수 있어야 한다.
 *
 * <p>키 형식 자체(왕복·GCM 랜덤 IV 등)의 검증은
 * {@code InsurancePiiEncryptionConverterTest} 가, 실제 DB 저장 형태 검증은
 * {@code PiiEncryptionIT} 가 담당한다.
 */
class InsuranceEncKeyFailFastTest {

    /** 환경변수 이름 자체가 진단 단서다 — 메시지에서 이 문자열이 사라지면 사고가 재발한다. */
    private static final String ENV_NAME = "INSURANCE_ENC_KEY";

    private static String base64Key(int bytes) {
        return Base64.getEncoder().encodeToString(new byte[bytes]);
    }

    // ────────────────────────────────────────────────────────────────────
    // InsuranceEncConfig — 부팅 시점 키 검증
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Base64 가 아닌 키 — 예외 메시지에 INSURANCE_ENC_KEY 가 그대로 등장한다")
    void rejectsNonBase64KeyWithDiagnosableMessage() {
        assertThatThrownBy(() -> new InsuranceEncConfig("이건 Base64 가 아니다!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ENV_NAME);
    }

    @Test
    @DisplayName("길이가 32바이트가 아닌 키 — 메시지에 INSURANCE_ENC_KEY 와 실제 길이가 함께 나온다")
    void rejectsWrongLengthKeyWithDiagnosableMessage() {
        // 16바이트 = AES-128. 유효한 Base64 이므로 디코딩은 통과하고 길이 검사에서 걸린다.
        assertThatThrownBy(() -> new InsuranceEncConfig(base64Key(16)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ENV_NAME)
                .hasMessageContaining("32")
                .hasMessageContaining("16");
    }

    @Test
    @DisplayName("빈 문자열 키 — 0바이트로 디코딩되어 길이 검사에서 걸린다")
    void rejectsEmptyKey() {
        assertThatThrownBy(() -> new InsuranceEncConfig(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ENV_NAME);
    }

    @Test
    @DisplayName("정상 32바이트 키 — 앞뒤 공백이 있어도 트리밍되어 통과한다")
    void acceptsValidThirtyTwoByteKey() {
        assertThatCode(() -> new InsuranceEncConfig("  " + base64Key(32) + "  "))
                .doesNotThrowAnyException();
    }

    // ────────────────────────────────────────────────────────────────────
    // 컨버터 기본 생성자 — 루트 build.gradle.kts 의 test env 배선 검증
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("컨버터 기본 생성자가 env 에서 키를 읽어낸다 — 루트 build.gradle.kts test env 배선이 살아있다는 증거")
    void defaultConstructorResolvesKeyFromEnvironment() {
        // Hibernate 는 컨버터를 기본 생성자로 인스턴스화한다. 즉 이 경로가 곧 운영 경로다.
        // 루트 build.gradle.kts 의 tasks.withType<Test> env 블록에 INSURANCE_ENC_KEY 가
        // 없으면 여기서 IllegalStateException 이 터진다.
        assertThat(System.getenv(ENV_NAME))
                .as("루트 build.gradle.kts 의 tasks.withType<Test> env 블록에 %s 주입 필요", ENV_NAME)
                .isNotBlank();

        InsurancePiiEncryptionConverter converter = new InsurancePiiEncryptionConverter();

        // env 키로 실제 암복호화가 되는지까지 확인 — 키가 32바이트로 제대로 디코딩됐다는 뜻.
        String plain = "900101-1234567";
        String encrypted = converter.convertToDatabaseColumn(plain);
        assertThat(encrypted).startsWith("enc:v1:").doesNotContain(plain);
        assertThat(converter.convertToEntityAttribute(encrypted)).isEqualTo(plain);
    }
}
