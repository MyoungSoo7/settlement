package github.lms.lemuel.ai.chat.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskerTest {

    // Luhn 통과 테스트 카드번호(Visa 4111…, Mastercard 5555…).
    private static final String VISA = "4111111111111111";
    private static final String MASTERCARD = "5555555555554444";

    @Test
    @DisplayName("카드번호(Luhn 통과) — 구분자 유무와 무관하게 마스킹")
    void masksCardNumbers() {
        assertThat(PiiMasker.mask("제 카드 " + VISA + " 입니다"))
                .doesNotContain("4111").contains("[카드번호 마스킹됨]");
        assertThat(PiiMasker.mask("4111-1111-1111-1111 로 결제"))
                .doesNotContain("4111").contains("[카드번호 마스킹됨]");
        assertThat(PiiMasker.mask("4111 1111 1111 1111"))
                .doesNotContain("1111").contains("[카드번호 마스킹됨]");
        assertThat(PiiMasker.mask(MASTERCARD)).doesNotContain("5555");
    }

    @Test
    @DisplayName("주민등록번호 — 형식+성별자리 감지 시 마스킹")
    void masksResidentRegistrationNumbers() {
        assertThat(PiiMasker.mask("주민번호 901010-1234567 에요"))
                .doesNotContain("901010").doesNotContain("1234567").contains("[주민번호 마스킹됨]");
        assertThat(PiiMasker.mask("9010101234567"))
                .doesNotContain("9010101234567").contains("[주민번호 마스킹됨]");
    }

    @Test
    @DisplayName("한 문장에 카드+주민번호 동시 — 둘 다 마스킹")
    void masksBothInOneSentence() {
        String masked = PiiMasker.mask("카드 " + VISA + " 주민 901010-1234567 확인");
        assertThat(masked).contains("[카드번호 마스킹됨]").contains("[주민번호 마스킹됨]")
                .doesNotContain("4111").doesNotContain("901010");
    }

    @Test
    @DisplayName("Luhn 실패 숫자열(주문번호 등) — 마스킹하지 않는다(오탐 방지)")
    void doesNotMaskNonLuhnDigits() {
        // 16자리지만 Luhn 실패(합=64) — 카드가 아니므로 원문 유지.
        String orderNo = "1234567890123456";
        assertThat(PiiMasker.mask("주문번호 " + orderNo)).contains(orderNo);
    }

    @Test
    @DisplayName("일반 텍스트·짧은 숫자(전화·금액) — 변경 없음")
    void leavesPlainTextUnchanged() {
        assertThat(PiiMasker.mask("정산 주기 알려줘")).isEqualTo("정산 주기 알려줘");
        assertThat(PiiMasker.mask("VIP 수수료는 2.5% 입니다")).isEqualTo("VIP 수수료는 2.5% 입니다");
        assertThat(PiiMasker.mask("전화 010-1234-5678")).contains("010-1234-5678");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("null/blank — 그대로 반환")
    void passesThroughNullAndBlank(String input) {
        assertThat(PiiMasker.mask(input)).isEqualTo(input);
    }
}
