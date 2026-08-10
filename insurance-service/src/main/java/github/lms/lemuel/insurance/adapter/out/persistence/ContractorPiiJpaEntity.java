package github.lms.lemuel.insurance.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * 계약자 PII(연락처) 영속 객체 — 별도 테이블 분리 저장.
 *
 * <p>암호화: {@code InsurancePiiEncryptionConverter} 가 {@code encrypted_phone} 필드에 자동 적용됨.
 * Hibernate 가 DB 에서 로드할 때 복호화, 저장할 때 암호화한다(도메인 무오염).
 *
 * <p>평문 연락처는 절대 평문 칼럼에 저장하지 않는다 — 개인정보 보호 & 규제 준수(PII 분리).
 */
@Entity
@Table(name = "contractor_pii", schema = "opslab")
public class ContractorPiiJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 청약 ID — insurance_applications.application_id 와 1:1 대응 (UNIQUE 제약). */
    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    /**
     * 계약자 연락처 (암호화 보관).
     *
     * <p>데이터베이스 칼럼: {@code encrypted_phone TEXT} → AES-GCM "enc:v1:" 접두 + Base64.
     * Hibernate converter 가 자동 적용되므로, 도메인에서는 평문 문자열로 다룬다.
     */
    @Column(name = "encrypted_phone", nullable = false)
    @Convert(converter = InsurancePiiEncryptionConverter.class)
    private String encryptedPhone;

    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    private ZonedDateTime createdAt;

    // ──── 기본 생성자 (Hibernate 용) ────
    protected ContractorPiiJpaEntity() {
    }

    // ──── 팩토리 (도메인에서 사용) ────
    public ContractorPiiJpaEntity(UUID applicationId, String plainPhone) {
        this.applicationId = applicationId;
        this.encryptedPhone = plainPhone;  // converter 가 저장 시 암호화
    }

    // ──── 접근자 ────
    public Long getId() {
        return id;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public String getEncryptedPhone() {
        return encryptedPhone;  // converter 가 로드 시 복호화해 반환
    }

    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }
}
