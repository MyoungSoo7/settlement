package github.lms.lemuel.insurance.integration;

import github.lms.lemuel.insurance.adapter.out.persistence.ContractorPiiJpaEntity;
import github.lms.lemuel.insurance.adapter.out.persistence.ContractorPiiRepository;
import github.lms.lemuel.insurance.adapter.out.persistence.InsuredPersonPiiJpaEntity;
import github.lms.lemuel.insurance.adapter.out.persistence.InsuredPersonPiiRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PII 평문 저장 방지 통합테스트 — 실 PostgreSQL(Testcontainers) 의 raw 칼럼을 네이티브 쿼리로 직접 읽어
 * 피보험자 주민등록번호·계약자 연락처가 <b>평문으로 저장되지 않음</b>을 증명한다.
 *
 * <p>단위테스트({@code InsurancePiiEncryptionConverterTest})는 컨버터 함수의 입출력만 검증하므로,
 * "실제로 Hibernate 가 컨버터를 태워서 DB 에 암호문을 쓰는가"는 증명하지 못한다. 이 테스트는 그 간극을 메운다:
 *
 * <ol>
 *   <li>리포지토리로 평문을 저장 → JdbcTemplate 네이티브 쿼리로 raw 칼럼을 읽어
 *       {@code enc:v1:} 접두 + Base64 형태이고 평문이 어디에도 없음을 확인</li>
 *   <li>같은 행을 리포지토리로 다시 읽어 평문이 정확히 복원됨을 확인(왕복)</li>
 *   <li>같은 평문을 두 행에 저장했을 때 raw 암호문이 서로 달라, DB 덤프만으로 값 동일성을
 *       추론할 수 없음을 확인(GCM 랜덤 IV)</li>
 *   <li>테이블 전체를 훑어 평문 조각이 단 한 행도 없음을 확인</li>
 * </ol>
 *
 * <p>키는 env {@code INSURANCE_ENC_KEY}(루트 {@code build.gradle.kts} 의 {@code tasks.withType<Test>}
 * env 블록에서 주입)에서 로드된다. 복호화가 성공한다는 것 자체가 그 배선이 살아있다는 증거다.
 */
class PiiEncryptionIT extends InsuranceIntegrationTestSupport {

    /** 검증용 평문 — DB 어디에도 이 문자열이 나타나면 안 된다. */
    private static final String PLAIN_RRN = "900101-1234567";
    private static final String PLAIN_PHONE = "010-9876-5432";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    InsuredPersonPiiRepository insuredPersonPiiRepository;

    @Autowired
    ContractorPiiRepository contractorPiiRepository;

    // ────────────────────────────────────────────────────────────────────
    // 피보험자 주민등록번호
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("피보험자 주민등록번호 — 네이티브 쿼리로 읽은 raw 칼럼이 암호문이고 평문을 담고 있지 않다")
    void insuredRrnIsNeverStoredAsPlaintext() {
        UUID applicationId = UUID.randomUUID();
        insuredPersonPiiRepository.save(new InsuredPersonPiiJpaEntity(applicationId, PLAIN_RRN));

        // Hibernate 를 우회한 네이티브 쿼리 — DB 에 실제로 적힌 바이트를 그대로 읽는다.
        String raw = jdbc.queryForObject(
                "SELECT encrypted_rrn FROM opslab.insured_person_pii WHERE application_id = ?",
                String.class, applicationId);

        assertThat(raw)
                .as("컨버터가 태워지지 않으면 평문이 그대로 남는다")
                .doesNotContain(PLAIN_RRN)
                .doesNotContain("900101")
                .startsWith("enc:v1:");

        // 접두 뒤는 Base64(IV || ciphertext+tag) — IV 12바이트 + 태그 16바이트보다 길어야 한다.
        byte[] blob = Base64.getDecoder().decode(raw.substring("enc:v1:".length()));
        assertThat(blob.length).isGreaterThan(12 + 16);
    }

    @Test
    @DisplayName("피보험자 주민등록번호 — 리포지토리로 다시 읽으면 평문이 정확히 복원된다 (왕복)")
    void insuredRrnRoundTripsThroughConverter() {
        UUID applicationId = UUID.randomUUID();
        insuredPersonPiiRepository.save(new InsuredPersonPiiJpaEntity(applicationId, PLAIN_RRN));

        String decrypted = insuredPersonPiiRepository.findByApplicationId(applicationId)
                .orElseThrow()
                .getEncryptedRrn();

        // 복호화 성공 = INSURANCE_ENC_KEY env 배선이 살아있다는 증거.
        assertThat(decrypted).isEqualTo(PLAIN_RRN);
    }

    // ────────────────────────────────────────────────────────────────────
    // 계약자 연락처
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("계약자 연락처 — 네이티브 쿼리로 읽은 raw 칼럼이 암호문이고 평문을 담고 있지 않다")
    void contractorPhoneIsNeverStoredAsPlaintext() {
        UUID applicationId = UUID.randomUUID();
        contractorPiiRepository.save(new ContractorPiiJpaEntity(applicationId, PLAIN_PHONE));

        String raw = jdbc.queryForObject(
                "SELECT encrypted_phone FROM opslab.contractor_pii WHERE application_id = ?",
                String.class, applicationId);

        assertThat(raw)
                .doesNotContain(PLAIN_PHONE)
                .doesNotContain("9876")
                .startsWith("enc:v1:");

        byte[] blob = Base64.getDecoder().decode(raw.substring("enc:v1:".length()));
        assertThat(blob.length).isGreaterThan(12 + 16);
    }

    @Test
    @DisplayName("계약자 연락처 — 리포지토리로 다시 읽으면 평문이 정확히 복원된다 (왕복)")
    void contractorPhoneRoundTripsThroughConverter() {
        UUID applicationId = UUID.randomUUID();
        contractorPiiRepository.save(new ContractorPiiJpaEntity(applicationId, PLAIN_PHONE));

        String decrypted = contractorPiiRepository.findByApplicationId(applicationId)
                .orElseThrow()
                .getEncryptedPhone();

        assertThat(decrypted).isEqualTo(PLAIN_PHONE);
    }

    // ────────────────────────────────────────────────────────────────────
    // 암호문 비결정성 · 전수 스캔
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 평문을 두 행에 저장해도 raw 암호문이 서로 다르다 — DB 덤프로 값 동일성 추론 불가 (GCM 랜덤 IV)")
    void sameRrnYieldsDifferentCiphertextPerRow() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        insuredPersonPiiRepository.save(new InsuredPersonPiiJpaEntity(first, PLAIN_RRN));
        insuredPersonPiiRepository.save(new InsuredPersonPiiJpaEntity(second, PLAIN_RRN));

        String rawFirst = jdbc.queryForObject(
                "SELECT encrypted_rrn FROM opslab.insured_person_pii WHERE application_id = ?",
                String.class, first);
        String rawSecond = jdbc.queryForObject(
                "SELECT encrypted_rrn FROM opslab.insured_person_pii WHERE application_id = ?",
                String.class, second);

        assertThat(rawFirst).isNotEqualTo(rawSecond);
        // 그럼에도 양쪽 모두 같은 평문으로 복호화된다.
        assertThat(insuredPersonPiiRepository.findByApplicationId(first).orElseThrow().getEncryptedRrn())
                .isEqualTo(insuredPersonPiiRepository.findByApplicationId(second).orElseThrow().getEncryptedRrn())
                .isEqualTo(PLAIN_RRN);
    }

    @Test
    @DisplayName("PII 테이블 전수 스캔 — 평문 조각을 담은 행이 단 하나도 없고 모든 행이 enc:v1: 접두를 갖는다")
    void noPiiRowLeaksPlaintext() {
        insuredPersonPiiRepository.save(new InsuredPersonPiiJpaEntity(UUID.randomUUID(), PLAIN_RRN));
        contractorPiiRepository.save(new ContractorPiiJpaEntity(UUID.randomUUID(), PLAIN_PHONE));

        List<String> rrnColumn =
                jdbc.queryForList("SELECT encrypted_rrn FROM opslab.insured_person_pii", String.class);
        List<String> phoneColumn =
                jdbc.queryForList("SELECT encrypted_phone FROM opslab.contractor_pii", String.class);

        assertThat(rrnColumn).isNotEmpty().allSatisfy(v ->
                assertThat(v).startsWith("enc:v1:").doesNotContain(PLAIN_RRN).doesNotContain("900101"));
        assertThat(phoneColumn).isNotEmpty().allSatisfy(v ->
                assertThat(v).startsWith("enc:v1:").doesNotContain(PLAIN_PHONE).doesNotContain("9876"));

        // 평문을 LIKE 로 훑어도 0건 — 운영 DB 덤프 유출 시나리오에 대한 최종 방어선.
        Long plaintextHits = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM opslab.insured_person_pii WHERE encrypted_rrn LIKE '%900101%')
                     + (SELECT count(*) FROM opslab.contractor_pii     WHERE encrypted_phone LIKE '%9876%')
                """, Long.class);
        assertThat(plaintextHits).isZero();
    }
}
