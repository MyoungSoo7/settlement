package github.lms.lemuel.insurance.adapter.out.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 보험 PII(피보험자 주민등록번호·계약자 연락처) 필드 암호화 컨버터 (adapter/out/persistence 배치, 도메인 무오염).
 *
 * <p>AES-GCM(256) 봉투 암호화. 저장 형식은 {@code enc:v1:} 접두 + {@code Base64(IV || ciphertext+tag)}.
 * 키는 env {@code INSURANCE_ENC_KEY}(Base64 32바이트 = AES-256)에서 로드한다 —
 * 기본값이 없어 미설정 시 Hibernate 인스턴스화 시점에 부팅이 실패한다(운영 fail-closed).
 * GCM nonce 는 매 암호화마다 12바이트 랜덤, 인증 태그는 128비트.
 *
 * <p>settlement-service 의 {@code PayoutFieldEncryptionConverter} 와 동형.
 *
 * <p><b>PII 저장 구조</b>: 피보험자·계약자 개인정보는 별도 테이블({@code insured_person_pii},
 * {@code contractor_pii})로 분리 저장. 평문은 도메인에서만 다루고, 영속 어댑터가 이 컨버터로
 * 암호화/복호화한다(도메인 무오염).
 */
@Converter
public class InsurancePiiEncryptionConverter implements AttributeConverter<String, String> {

    /** 암호화 스킴 버전 접두 — 이 접두가 없는 저장값은 평문으로 간주해 거부한다. */
    static final String PREFIX = "enc:v1:";

    private static final String ENV_KEY = "INSURANCE_ENC_KEY";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;   // AES-256
    private static final int IV_BYTES = 12;    // GCM 권장 nonce 길이
    private static final int TAG_BITS = 128;   // GCM 인증 태그 길이

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    /** Hibernate 가 인스턴스화하는 기본 생성자 — env 에서 키를 로드한다(미설정 시 부팅 실패). */
    public InsurancePiiEncryptionConverter() {
        this(resolveKeyFromEnv());
    }

    /** 테스트 전용: 고정 키 주입. */
    InsurancePiiEncryptionConverter(byte[] key) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalStateException(
                    ENV_KEY + " must decode to exactly " + KEY_BYTES + " bytes (AES-256). Got: "
                            + (key == null ? "null" : key.length + " bytes"));
        }
        this.keySpec = new SecretKeySpec(key, "AES");
    }

    private static byte[] resolveKeyFromEnv() {
        String raw = System.getenv(ENV_KEY);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    ENV_KEY + " is not set. A Base64-encoded 32-byte AES-256 key is required to boot "
                            + "(보험 PII 암호화 필수 — 운영 배포 시 주입).");
        }
        try {
            return Base64.getDecoder().decode(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(ENV_KEY + " must be valid Base64.", e);
        }
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("보험 PII 필드 암호화 실패", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        if (!dbData.startsWith(PREFIX)) {
            throw new IllegalStateException(
                    "보험 PII 저장값은 " + PREFIX + " 암호문이어야 합니다 (평문 복호화 거부)");
        }
        try {
            byte[] blob = Base64.getDecoder().decode(dbData.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES));
            byte[] plaintext = cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("보험 PII 필드 복호화 실패", e);
        }
    }
}
