package github.lms.lemuel.ai.chat.adapter.out.persistence;

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
 * 대화 메시지 본문(chat_messages.content) 암호화 컨버터 (adapter/out/persistence 배치, 도메인 무오염).
 *
 * <p>사용자 발화는 결제·정산 문의 과정에서 PII 를 포함할 수 있어(카드번호·주민번호는 {@code PiiMasker}
 * 초크포인트가 마스킹하지만, 이름·주소·연락처 등 비정형 PII 는 남는다) 평문 저장 위험이 크다.
 * settlement 의 지급계좌 암호화({@code PayoutFieldEncryptionConverter})와 동형의 앱단 봉투 암호화를
 * 적용해 저장 시점(at rest)의 유출 표면을 줄인다.
 *
 * <p>AES-GCM(256) 봉투 암호화. 저장 형식은 {@code enc:v1:} 접두 + {@code Base64(IV || ciphertext+tag)}.
 * 키는 env {@code CHAT_ENC_KEY}(Base64 32바이트 = AES-256)에서 로드한다 — JWT_SECRET 과 동일하게
 * 기본값이 없어 미설정 시 부팅이 실패한다(운영 fail-closed). GCM nonce 는 매 암호화마다 12바이트 랜덤,
 * 인증 태그는 128비트.
 *
 * <p><b>레거시 평문 lazy migration</b>: 복호화 대상 값이 {@code enc:v1:} 로 시작하지 않으면 암호화 도입
 * 이전의 평문으로 간주하고 그대로 반환한다. 기존 행을 일괄 재기록하지 않으며, 메시지는 append-only 라
 * 신규 저장분부터 암호문으로 적재된다. 컬럼은 TEXT 라 암호문 확장으로도 폭 제약이 없다.
 */
@Converter
public class ChatContentEncryptionConverter implements AttributeConverter<String, String> {

    /** 암호화 스킴 버전 접두 — 이 접두가 없으면 레거시 평문. */
    static final String PREFIX = "enc:v1:";
    private static final String ENV_KEY = "CHAT_ENC_KEY";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;   // AES-256
    private static final int IV_BYTES = 12;    // GCM 권장 nonce 길이
    private static final int TAG_BITS = 128;   // GCM 인증 태그 길이

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    /** Hibernate 가 인스턴스화하는 기본 생성자 — env 에서 키를 로드한다(미설정 시 부팅 실패). */
    public ChatContentEncryptionConverter() {
        this(resolveKeyFromEnv());
    }

    /** 테스트 전용: 고정 키 주입. */
    ChatContentEncryptionConverter(byte[] key) {
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
                            + "(대화 본문 PII 암호화 필수 — 운영 배포 시 주입).");
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
            throw new IllegalStateException("대화 본문 암호화 실패", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        if (!dbData.startsWith(PREFIX)) {
            // 레거시 평문(enc:v1 도입 이전 적재분) — lazy migration: 키 없이 그대로 반환.
            return dbData;
        }
        try {
            byte[] blob = Base64.getDecoder().decode(dbData.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES));
            byte[] plaintext = cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("대화 본문 복호화 실패", e);
        }
    }
}
