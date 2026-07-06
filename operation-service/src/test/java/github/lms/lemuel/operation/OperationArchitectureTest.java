package github.lms.lemuel.operation;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * operation-service 의 헥사고날 아키텍처 + MSA 코드 경계를 강제하는 가드 (loan-service 패턴).
 *
 * <p>핵심 불변식:
 * <ul>
 *   <li>도메인은 application/adapter/config 에 의존하지 않는다 (의존 방향).</li>
 *   <li>application 은 adapter 에 의존하지 않는다 (config 의 OpsProperties 주입은 허용 —
 *       config 는 조립 계층이지 adapter 가 아니다).</li>
 *   <li>★ operation-service 는 order/settlement/loan 패키지에 코드 의존 0
 *       — 신호는 Alertmanager webhook(Phase 1)·Kafka 이벤트(Phase 2)로만 수신한다.</li>
 * </ul>
 */
class OperationArchitectureTest {

    private static JavaClasses operationClasses;

    @BeforeAll
    static void importClasses() {
        operationClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel.operation");
    }

    @Test
    void 도메인은_application_과_adapter_와_config_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..operation..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..operation..application..", "..operation..adapter..", "..operation.config..")
                .allowEmptyShould(true);
        rule.check(operationClasses);
    }

    @Test
    void application_은_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..operation..application..")
                .should().dependOnClassesThat()
                .resideInAPackage("..operation..adapter..")
                .allowEmptyShould(true);
        rule.check(operationClasses);
    }

    @Test
    void operation_은_order_settlement_loan_에_코드의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.operation..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "github.lms.lemuel.order..",
                        "github.lms.lemuel.settlement..",
                        "github.lms.lemuel.loan..")
                .allowEmptyShould(true);
        rule.check(operationClasses);
    }
}
