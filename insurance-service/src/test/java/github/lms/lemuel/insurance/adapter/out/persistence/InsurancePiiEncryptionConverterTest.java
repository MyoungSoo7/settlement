package github.lms.lemuel.insurance.adapter.out.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * InsurancePiiEncryptionConverter 단위 테스트 — AES-256 GCM 암호화 왕복 / 평문 거부 / enc:v1 접두 구분.
 *
 * <p>고정 키 주입용 package-private 생성자를 사용해 env(INSURANCE_ENC_KEY) 의존 없이 검증한다.
 * PayoutFieldEncryptionConverter 와 동형.
 */
class InsurancePiiEncryptionConverterTest {

    private final InsurancePiiEncryptionConverter converter =
            new InsurancePiiEncryptionConverter(fixedKey());

    private static byte[] fixedKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        return key;
    }

    @Test
    void encrypts_with_enc_v1_prefix_and_roundtrips() {
        String plain = "900101-1234567";

        String encrypted = converter.convertToDatabaseColumn(plain);

        assertThat(encrypted).startsWith("enc:v1:");
        assertThat(encrypted).isNotEqualTo(plain);
        assertThat(converter.convertToEntityAttribute(encrypted)).isEqualTo(plain);
    }

    @Test
    void handles_unicode_rrn_and_phone() {
        String plain = "010-1234-5678";

        String encrypted = converter.convertToDatabaseColumn(plain);

        assertThat(converter.convertToEntityAttribute(encrypted)).isEqualTo(plain);
    }

    @Test
    void ciphertext_is_nondeterministic_due_to_random_iv() {
        String plain = "950205-2345678";

        String encrypted1 = converter.convertToDatabaseColumn(plain);
        String encrypted2 = converter.convertToDatabaseColumn(plain);

        // GCM nonce 는 매 암호화마다 랜덤이므로 ciphertext 가 다르다
        assertThat(encrypted1).isNotEqualTo(encrypted2);
        // 그러나 복호화하면 동일한 평문
        assertThat(converter.convertToEntityAttribute(encrypted1))
                .isEqualTo(converter.convertToEntityAttribute(encrypted2))
                .isEqualTo(plain);
    }

    @Test
    void plaintext_without_enc_v1_prefix_is_rejected() {
        String legacyPlaintext = "880301-1111111";

        assertThatThrownBy(() -> converter.convertToEntityAttribute(legacyPlaintext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enc:v1:");
    }

    @Test
    void nulls_pass_through_in_both_directions() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void rejects_key_of_wrong_length() {
        assertThatThrownBy(() -> new InsurancePiiEncryptionConverter(new byte[16]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void rejects_null_key() {
        assertThatThrownBy(() -> new InsurancePiiEncryptionConverter(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void encrypted_data_maintains_format_consistency() {
        // enc:v1: 접두가 있고, 그 뒤는 Base64 인코딩된 (IV + ciphertext+tag)
        String plain = "880101-1234567";
        String encrypted = converter.convertToDatabaseColumn(plain);

        assertThat(encrypted)
                .startsWith("enc:v1:")
                .isNotEmpty();
        assertThat(encrypted.length()).isGreaterThan(7);  // 적어도 접두보다는 길어야 함

        // Base64 만 포함 (printable)
        String base64Part = encrypted.substring(7);
        assertThat(base64Part).matches("[A-Za-z0-9+/=]*");
    }

    @Test
    void encryption_roundtrip_with_special_characters() {
        String plain = "010-1234-5678 (010번)";

        String encrypted = converter.convertToDatabaseColumn(plain);
        String decrypted = converter.convertToEntityAttribute(encrypted);

        assertThat(decrypted).isEqualTo(plain);
    }

    @Test
    void encryption_roundtrip_with_whitespace() {
        String plain = "  900101-1234567  ";

        String encrypted = converter.convertToDatabaseColumn(plain);
        String decrypted = converter.convertToEntityAttribute(encrypted);

        // 공백 포함 정확히 보존
        assertThat(decrypted).isEqualTo(plain);
    }

    @Test
    void empty_string_is_encrypted_not_passed_through() {
        String plain = "";

        String encrypted = converter.convertToDatabaseColumn(plain);

        // 빈 문자열도 암호화되어야 함 (null 이 아님)
        assertThat(encrypted).startsWith("enc:v1:");
        assertThat(converter.convertToEntityAttribute(encrypted)).isEmpty();
    }
}
