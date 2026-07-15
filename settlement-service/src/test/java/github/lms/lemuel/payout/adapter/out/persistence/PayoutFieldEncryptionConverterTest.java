package github.lms.lemuel.payout.adapter.out.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PayoutFieldEncryptionConverter 단위 테스트 — 암호화 왕복 / 레거시 평문 통과 / enc:v1 접두 구분.
 * 고정 키 주입용 package-private 생성자를 사용해 env 의존 없이 검증한다.
 */
class PayoutFieldEncryptionConverterTest {

    private final PayoutFieldEncryptionConverter converter =
            new PayoutFieldEncryptionConverter(fixedKey());

    private static byte[] fixedKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        return key;
    }

    @Test
    void encrypts_with_enc_v1_prefix_and_roundtrips() {
        String plain = "110-1234-567890";

        String encrypted = converter.convertToDatabaseColumn(plain);

        assertThat(encrypted).startsWith(PayoutFieldEncryptionConverter.PREFIX);
        assertThat(encrypted).isNotEqualTo(plain);
        assertThat(converter.convertToEntityAttribute(encrypted)).isEqualTo(plain);
    }

    @Test
    void handles_unicode_account_holder_name() {
        String plain = "홍길동";

        String encrypted = converter.convertToDatabaseColumn(plain);

        assertThat(converter.convertToEntityAttribute(encrypted)).isEqualTo(plain);
    }

    @Test
    void ciphertext_is_nondeterministic_due_to_random_iv() {
        String plain = "예금주-계좌";

        assertThat(converter.convertToDatabaseColumn(plain))
                .isNotEqualTo(converter.convertToDatabaseColumn(plain));
    }

    @Test
    void legacy_plaintext_without_prefix_passes_through_unchanged() {
        // enc:v1 도입 이전 평문 — lazy migration: 복호화 없이 그대로 반환.
        String legacyPlaintext = "110-9999-000000";

        assertThat(converter.convertToEntityAttribute(legacyPlaintext)).isEqualTo(legacyPlaintext);
    }

    @Test
    void nulls_pass_through_in_both_directions() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void rejects_key_of_wrong_length() {
        assertThatThrownBy(() -> new PayoutFieldEncryptionConverter(new byte[16]))
                .isInstanceOf(IllegalStateException.class);
    }
}
