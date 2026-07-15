package github.lms.lemuel.ai.chat.adapter.out.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ChatContentEncryptionConverter 단위 테스트 — 암호화 왕복 / 레거시 평문 통과 / enc:v1 접두 구분 /
 * 한국어 유니코드 / 키 길이 검증. 고정 키 주입용 package-private 생성자로 env 의존 없이 검증한다
 * (settlement PayoutFieldEncryptionConverterTest 동형).
 */
class ChatContentEncryptionConverterTest {

    private final ChatContentEncryptionConverter converter =
            new ChatContentEncryptionConverter(fixedKey());

    private static byte[] fixedKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        return key;
    }

    @Test
    void encrypts_with_enc_v1_prefix_and_roundtrips() {
        String plain = "정산 주기가 어떻게 되나요?";

        String encrypted = converter.convertToDatabaseColumn(plain);

        assertThat(encrypted).startsWith(ChatContentEncryptionConverter.PREFIX);
        assertThat(encrypted).isNotEqualTo(plain);
        assertThat(converter.convertToEntityAttribute(encrypted)).isEqualTo(plain);
    }

    @Test
    void handles_korean_unicode_and_emoji_content() {
        String plain = "홍길동입니다. 서울시 강남구 테헤란로 123 문의드려요 🙂";

        String encrypted = converter.convertToDatabaseColumn(plain);

        assertThat(converter.convertToEntityAttribute(encrypted)).isEqualTo(plain);
    }

    @Test
    void ciphertext_is_nondeterministic_due_to_random_iv() {
        String plain = "같은 문장도 매번 다른 암호문";

        assertThat(converter.convertToDatabaseColumn(plain))
                .isNotEqualTo(converter.convertToDatabaseColumn(plain));
    }

    @Test
    void legacy_plaintext_without_prefix_passes_through_unchanged() {
        // enc:v1 도입 이전 평문 — lazy migration: 복호화 없이 그대로 반환.
        String legacyPlaintext = "암호화 도입 이전에 저장된 평문 메시지";

        assertThat(converter.convertToEntityAttribute(legacyPlaintext)).isEqualTo(legacyPlaintext);
    }

    @Test
    void nulls_pass_through_in_both_directions() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void rejects_key_of_wrong_length() {
        assertThatThrownBy(() -> new ChatContentEncryptionConverter(new byte[16]))
                .isInstanceOf(IllegalStateException.class);
    }
}
