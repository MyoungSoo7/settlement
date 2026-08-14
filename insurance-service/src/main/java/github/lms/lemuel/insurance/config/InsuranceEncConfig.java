package github.lms.lemuel.insurance.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;

/**
 * 보험 PII 암호화 키 유효성 검증 — 부팅 시점 Fail-Fast.
 *
 * <p>키는 env {@code INSURANCE_ENC_KEY}(Base64 32바이트 = AES-256)에서 로드한다.
 * <b>기본값 없음</b> — 미설정 시 Spring 이 "Could not resolve placeholder 'INSURANCE_ENC_KEY'" 메시지로
 * 즉시 기동 실패(settlement payout PAYOUT_ENC_KEY 와 동일 패턴).
 *
 * <p>settlement-service 지급계좌 암호화에서 키 미설정 → 원인불명 CrashLoop 이 발생한 전례가 있다.
 * insurance-service 는 이 빈이 부팅 시점에 키를 검증함으로써 동일 장애를 예방한다.
 *
 * <p>실제 암호화는 {@code InsurancePiiEncryptionConverter}(Hibernate AttributeConverter) 가 수행한다.
 */
@Configuration
public class InsuranceEncConfig {

    private static final int REQUIRED_KEY_BYTES = 32; // AES-256

    /**
     * Spring 이 {@code ${INSURANCE_ENC_KEY}} 를 해석하지 못하면
     * {@code PropertySourcesPropertyResolver} 가 {@code IllegalArgumentException}(Could not resolve
     * placeholder 'INSURANCE_ENC_KEY' in value "${INSURANCE_ENC_KEY}")을 던져 부팅이 실패한다.
     */
    public InsuranceEncConfig(
            @Value("${app.insurance.enc-key}") String encKeyBase64) {

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encKeyBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "INSURANCE_ENC_KEY 는 유효한 Base64 문자열이어야 합니다 (AES-256 = 32바이트).", e);
        }
        if (keyBytes.length != REQUIRED_KEY_BYTES) {
            throw new IllegalStateException(
                    "INSURANCE_ENC_KEY must decode to exactly " + REQUIRED_KEY_BYTES
                            + " bytes (AES-256). Got: " + keyBytes.length + " bytes.");
        }
    }
}
