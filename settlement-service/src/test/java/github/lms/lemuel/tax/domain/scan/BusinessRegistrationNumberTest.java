package github.lms.lemuel.tax.domain.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사업자등록번호 VO — OCR 오인식을 <b>예외가 아니라 판정</b>으로 다룬다.
 *
 * <p>스캔본에서 뽑은 값은 신뢰할 수 없는 입력이므로 생성 자체는 실패하지 않고,
 * {@link BusinessRegistrationNumber#isValid()} 체크섬 판정으로 리뷰 필요 여부를 가른다.
 */
class BusinessRegistrationNumberTest {

    @Test
    @DisplayName("체크섬을 통과하는 10자리는 유효")
    void validChecksum() {
        BusinessRegistrationNumber brn = BusinessRegistrationNumber.of("101-81-00001");

        assertThat(brn.isPresent()).isTrue();
        assertThat(brn.isValid()).isTrue();
        assertThat(brn.digits()).isEqualTo("1018100001");
    }

    @Test
    @DisplayName("하이픈·공백이 섞여도 숫자만 정규화해 판정한다")
    void normalizesSeparators() {
        assertThat(BusinessRegistrationNumber.of(" 101 81 00001 ").isValid()).isTrue();
        assertThat(BusinessRegistrationNumber.of("1018100001").isValid()).isTrue();
    }

    @Test
    @DisplayName("체크섬이 어긋난 번호는 무효 — 생성은 성공한다(OCR 오인식은 예외가 아니라 판정)")
    void invalidChecksum() {
        BusinessRegistrationNumber brn = BusinessRegistrationNumber.of("101-81-00002");

        assertThat(brn.isPresent()).isTrue();
        assertThat(brn.isValid()).isFalse();
    }

    @Test
    @DisplayName("자릿수가 10이 아니면 무효")
    void wrongLength() {
        assertThat(BusinessRegistrationNumber.of("101-81-0000").isValid()).isFalse();
        assertThat(BusinessRegistrationNumber.of("101810000123").isValid()).isFalse();
    }

    @Test
    @DisplayName("null·공백·숫자 없음은 미존재로 다룬다")
    void absent() {
        assertThat(BusinessRegistrationNumber.of(null).isPresent()).isFalse();
        assertThat(BusinessRegistrationNumber.of("   ").isPresent()).isFalse();
        assertThat(BusinessRegistrationNumber.of("인식실패").isPresent()).isFalse();
        assertThat(BusinessRegistrationNumber.of(null).isValid()).isFalse();
        assertThat(BusinessRegistrationNumber.of(null).digits()).isNull();
    }

    @Test
    @DisplayName("마스킹 — 뒤 5자리는 노출하지 않는다(PII)")
    void masked() {
        assertThat(BusinessRegistrationNumber.of("101-81-00001").masked()).isEqualTo("101-81-*****");
        assertThat(BusinessRegistrationNumber.of(null).masked()).isEqualTo("-");
        // 10자리가 아니어도 원문을 그대로 흘리지 않는다
        assertThat(BusinessRegistrationNumber.of("12345").masked()).isEqualTo("*****");
    }

    @Test
    @DisplayName("toString 은 마스킹된 값만 — 로그로 PII 가 새지 않는다")
    void toStringIsMasked() {
        assertThat(BusinessRegistrationNumber.of("101-81-00001").toString()).doesNotContain("00001");
    }

    @Test
    @DisplayName("같은 숫자열이면 같은 값 객체")
    void valueEquality() {
        assertThat(BusinessRegistrationNumber.of("101-81-00001"))
                .isEqualTo(BusinessRegistrationNumber.of("1018100001"))
                .hasSameHashCodeAs(BusinessRegistrationNumber.of("1018100001"));
        assertThat(BusinessRegistrationNumber.of("101-81-00001"))
                .isNotEqualTo(BusinessRegistrationNumber.of("101-81-00002"));
    }
}
