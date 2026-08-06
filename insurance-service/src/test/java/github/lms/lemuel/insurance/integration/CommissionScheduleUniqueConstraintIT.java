package github.lms.lemuel.insurance.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D4 — {@code commission_schedules} 의 유니크 제약이 <b>실 PostgreSQL 에서</b> 강제되는지 검증한다.
 *
 * <p>D4 는 "선지급 12회는 12행" 이고, 유니크 제약은
 * {@code UNIQUE(policy_id, recipient_type, fc_id, installment_no)} <b>하나로 통일</b>한다.
 * 폐기된 안({@code UNIQUE(policy_id, recipient_type, fc_id)} / {@code UNIQUE(policy_id, installment_no)})
 * 은 12행 모델과 모순이라 하나라도 살아있으면 12행 삽입 자체가 불가능해진다.
 *
 * <p>도메인 코드의 규칙(Java 팩토리·검증기)이 아니라 <b>DB 가 마지막 방어선</b>이라는 점을 확인하는 것이
 * 이 클래스의 목적이다. 애플리케이션이 우회하거나 두 인스턴스가 동시에 스케줄을 만들어도 중복 회차는
 * DB 가 거부해야 한다(3단 멱등성 L3).
 *
 * <p>검증 축:
 * <ul>
 *   <li>중복 4-튜플 삽입 → {@link DataIntegrityViolationException} (제약명 {@code uq_commission_schedule})</li>
 *   <li>네 컬럼 각각이 <em>식별력</em>을 갖는다 — 하나만 달라도 공존 가능</li>
 *   <li>12회차가 한 계약·한 설계사 아래 모두 공존 (D4 의 본래 목적)</li>
 *   <li>pg_catalog 상 폐기된 두 유니크 조합이 존재하지 않는다</li>
 *   <li>D5 로 nullable 인 {@code recipient_type} 이 NULL 이면 SQL 표준상 중복이 걸러지지 않는다 —
 *       이번 범위가 항상 {@code 'FC'} 를 채우는 이유</li>
 * </ul>
 */
class CommissionScheduleUniqueConstraintIT extends InsuranceIntegrationTestSupport {

    /** D4 로 확정된 유일한 유니크 제약. */
    private static final String D4_CONSTRAINT = "uq_commission_schedule";

    /** D4 로 확정된 유니크 컬럼 조합. */
    private static final String D4_COLUMNS = "policy_id, recipient_type, fc_id, installment_no";

    /** 폐기된 안 — 이 조합이 살아있으면 한 계약·한 설계사에 12행을 넣을 수 없다. */
    private static final String DISCARDED_WITHOUT_INSTALLMENT = "policy_id, recipient_type, fc_id";

    /** 폐기된 안 — 이 조합이 살아있으면 계층 수령자(D5 여지)가 같은 회차를 가질 수 없다. */
    private static final String DISCARDED_WITHOUT_RECIPIENT = "policy_id, installment_no";

    private static final String INSERT = """
            INSERT INTO opslab.commission_schedules
                (commission_id, policy_id, fc_id, recipient_type, installment_no,
                 installment_amount, first_year_total, due_date, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, '2026-09-01', 'SCHEDULED')
            """;

    /** 초년도 총액 — 12 로 나누어떨어지므로 회차액은 균등하다(나머지 가산 규칙은 도메인 테스트 몫). */
    private static final BigDecimal FIRST_YEAR_TOTAL = new BigDecimal("1200000.00");
    private static final BigDecimal INSTALLMENT_AMOUNT = new BigDecimal("100000.00");

    /** 인덱스 정의문에서 괄호 안 컬럼 목록만 뽑는다. */
    private static final Pattern INDEX_COLUMNS = Pattern.compile("\\(([^)]*)\\)\\s*$");

    @Autowired
    JdbcTemplate jdbc;

    // ────────────────────────────────────────────────────────────────────
    // 중복 거부 — 이 AC 의 본체
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("동일 (policy_id, recipient_type, fc_id, installment_no) 재삽입 → DataIntegrityViolationException")
    void duplicateInstallmentRowIsRejectedByDatabase() {
        UUID policyId = UUID.randomUUID();

        insertInstallment(policyId, "fc-dup", "FC", 1);

        // commission_id 를 새로 발급해도 4-튜플이 같으면 DB 가 막는다 — 재시도·중복 발행 방어.
        assertThatThrownBy(() -> insertInstallment(policyId, "fc-dup", "FC", 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(D4_CONSTRAINT);

        assertThat(countRows(policyId))
                .as("거부된 삽입이 실제로 쓰이지 않았는지")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("12회차는 한 계약·한 설계사 아래 모두 공존한다 (D4: 선지급 12회 = 12행)")
    void twelveInstallmentsCoexistForSamePolicyAndFc() {
        UUID policyId = UUID.randomUUID();

        for (int installmentNo = 1; installmentNo <= 12; installmentNo++) {
            insertInstallment(policyId, "fc-twelve", "FC", installmentNo);
        }

        assertThat(countRows(policyId)).isEqualTo(12L);
    }

    // ────────────────────────────────────────────────────────────────────
    // 네 컬럼 각각의 식별력 — 하나만 달라도 공존해야 한다
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("policy_id 만 다르면 같은 회차도 공존한다")
    void differentPolicyIdCoexists() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        insertInstallment(first, "fc-policy", "FC", 3);
        insertInstallment(second, "fc-policy", "FC", 3);

        assertThat(countRows(first)).isEqualTo(1L);
        assertThat(countRows(second)).isEqualTo(1L);
    }

    @Test
    @DisplayName("fc_id 만 다르면 같은 회차도 공존한다 — 설계사별 귀속이 분리된다")
    void differentFcIdCoexists() {
        UUID policyId = UUID.randomUUID();

        insertInstallment(policyId, "fc-alpha", "FC", 3);
        insertInstallment(policyId, "fc-beta", "FC", 3);

        assertThat(countRows(policyId)).isEqualTo(2L);
    }

    @Test
    @DisplayName("recipient_type 만 다르면 같은 회차도 공존한다 — D5 계층 수수료 스키마 여지")
    void differentRecipientTypeCoexists() {
        UUID policyId = UUID.randomUUID();

        // 이번 범위의 계산 로직은 FC 만 채우지만(D5), 스키마는 상위 수령자 행을 받아들일 수 있어야 한다.
        insertInstallment(policyId, "fc-recipient", "FC", 3);
        insertInstallment(policyId, "fc-recipient", "MANAGER", 3);

        assertThat(countRows(policyId)).isEqualTo(2L);
    }

    @Test
    @DisplayName("recipient_type 이 NULL 이면 유니크가 중복을 걸러내지 못한다 — 이번 범위가 항상 'FC' 를 채우는 이유")
    void nullRecipientTypeDoesNotCollide() {
        UUID policyId = UUID.randomUUID();

        // SQL 표준상 NULL 은 서로 같지 않다 → 같은 (policy_id, fc_id, installment_no) 라도 둘 다 들어간다.
        // D5 가 컬럼을 nullable 로 둔 대가이며, 그래서 D5 는 "이번 범위에선 항상 FC" 를 못박았다.
        insertInstallment(policyId, "fc-null", null, 3);
        insertInstallment(policyId, "fc-null", null, 3);

        assertThat(countRows(policyId))
                .as("NULL 은 유니크 비교에서 서로 다르게 취급된다")
                .isEqualTo(2L);
    }

    // ────────────────────────────────────────────────────────────────────
    // 카탈로그 — 제약이 "하나로 통일" 되었는지 (폐기안 부재 확인)
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D4 조합의 유니크 제약이 존재하고, 폐기된 두 조합은 존재하지 않는다")
    void exactlyTheD4UniqueCombinationIsDeclared() {
        // 실패 시 "무엇이 대신 걸려 있었는지"는 AssertJ 가 실제 목록을 그대로 출력해 준다.
        List<String> uniqueColumnSets = uniqueIndexColumnSets();

        assertThat(uniqueColumnSets)
                .as("D4 로 확정된 유니크 조합이 스키마에 없다")
                .contains(D4_COLUMNS);
        assertThat(uniqueColumnSets)
                .as("폐기안이 살아있으면 한 계약·한 설계사에 12행을 넣을 수 없다")
                .doesNotContain(DISCARDED_WITHOUT_INSTALLMENT);
        assertThat(uniqueColumnSets)
                .as("폐기안이 살아있으면 계층 수령자가 같은 회차를 가질 수 없다")
                .doesNotContain(DISCARDED_WITHOUT_RECIPIENT);
    }

    @Test
    @DisplayName("D4 유니크는 인덱스가 아니라 테이블 제약(pg_constraint contype='u')으로 선언되어 있다")
    void d4UniqueIsDeclaredAsTableConstraint() {
        Long declared = jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_constraint con
                JOIN pg_class c ON c.oid = con.conrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'opslab'
                  AND c.relname = 'commission_schedules'
                  AND con.contype = 'u'
                  AND con.conname = ?
                """, Long.class, D4_CONSTRAINT);

        assertThat(declared).isEqualTo(1L);
    }

    // ────────────────────────────────────────────────────────────────────

    private void insertInstallment(UUID policyId, String fcId, String recipientType, int installmentNo) {
        jdbc.update(INSERT, UUID.randomUUID(), policyId, fcId, recipientType,
                installmentNo, INSTALLMENT_AMOUNT, FIRST_YEAR_TOTAL);
    }

    private Long countRows(UUID policyId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM opslab.commission_schedules WHERE policy_id = ?",
                Long.class, policyId);
    }

    /** commission_schedules 의 유니크 인덱스들을 "컬럼1, 컬럼2, …" 문자열 목록으로 돌려준다. */
    private List<String> uniqueIndexColumnSets() {
        return jdbc.query("""
                        SELECT pg_get_indexdef(x.indexrelid) AS indexdef
                        FROM pg_index x
                        JOIN pg_class c ON c.oid = x.indrelid
                        JOIN pg_class i ON i.oid = x.indexrelid
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'opslab'
                          AND c.relname = 'commission_schedules'
                          AND x.indisunique
                        """,
                (rs, rowNum) -> columnsOf(rs.getString("indexdef")));
    }

    private static String columnsOf(String indexDef) {
        Matcher matcher = INDEX_COLUMNS.matcher(indexDef.trim());
        assertThat(matcher.find())
                .as("인덱스 정의에서 컬럼 목록을 못 읽었다: %s", indexDef)
                .isTrue();
        return matcher.group(1).replaceAll("\\s+", " ").trim();
    }
}
