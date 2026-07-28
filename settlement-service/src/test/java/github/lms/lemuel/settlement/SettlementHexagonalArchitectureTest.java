package github.lms.lemuel.settlement;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * settlement-service 의 헥사고날 의존 방향 + MSA 코드 경계를 강제하는 가드 (account/loan 과 대칭).
 *
 * <p>settlement-service 는 다수의 바운디드 컨텍스트(settlement·payout·ledger·chargeback·
 * pgreconciliation·recovery·report·tax·integrity)를 담으므로, 컨텍스트별이 아니라 레이어 패키지
 * 세그먼트(`..domain..` / `..application..` / `..adapter..`)로 전 컨텍스트를 한 번에 강제한다.
 * shared-common(`..common..`)은 스코프에서 제외한다.
 *
 * <p>핵심 불변식:
 * <ul>
 *   <li>도메인은 application/adapter 에 의존하지 않는다 (의존 방향 — 헥사고날 핵심).</li>
 *   <li>application 은 adapter 에 의존하지 않는다 (포트 우회 금지).</li>
 *   <li>★ settlement-service 는 order/payment 패키지에 코드 의존 0
 *       — order 데이터는 Kafka 프로젝션(settlement_*_view) + /internal/recon 으로만 (ADR 0020, DB-per-service 경계).</li>
 * </ul>
 *
 * <p>(기존 {@link SettlementProjectionArchitectureTest} 와 동일하게 프로그래매틱 임포터 +
 * {@code allowEmptyShould} 로 작성 — 툴체인/바이트코드 버전 차이에 견고.)
 */
class SettlementHexagonalArchitectureTest {

    private static JavaClasses settlementClasses;

    @BeforeAll
    static void importClasses() {
        settlementClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption((Location location) ->
                        !location.contains("/generated/") && !location.contains("/build/generated"))
                .importPackages("github.lms.lemuel");
    }

    @Test
    void 도메인은_application_과_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .and().resideOutsideOfPackage("github.lms.lemuel.common..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..application..", "..adapter..")
                .allowEmptyShould(true);
        rule.check(settlementClasses);
    }

    @Test
    void application_은_adapter_에_의존하지_않는다() {
        // (감사 MED-1 해소) 과거 out-port(SettlementReconciliationQueryPort·SettlementSearchQueryPort·
        // SettlementSummaryQueryPort)가 adapter.out.persistence.querydsl.dto 의 DTO 7종을 반환 타입으로
        // 참조하던 application→adapter 역의존 20건은, 해당 DTO 들을 application.port.out.dto 로 이전해 제거됨.
        // 이제 이 규칙이 실효 강제된다(account-service 대칭). ArchUnit 은 1.4.1 이라야 Java25 바이트코드를 파싱한다.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application..")
                .and().resideOutsideOfPackage("github.lms.lemuel.common..")
                .should().dependOnClassesThat()
                .resideInAPackage("..adapter..")
                .allowEmptyShould(true);
        rule.check(settlementClasses);
    }

    @Test
    void settlement_은_order_payment_에_코드의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel..")
                .and().resideOutsideOfPackage("github.lms.lemuel.common..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "github.lms.lemuel.order..",
                        "github.lms.lemuel.payment..")
                .allowEmptyShould(true);
        rule.check(settlementClasses);
    }
}
