package github.lms.lemuel.investment;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * investment-service 의 헥사고날 아키텍처 + MSA 코드 경계를 강제하는 가드.
 *
 * <ul>
 *   <li>도메인은 application/adapter 에 의존하지 않는다 (의존 방향).</li>
 *   <li>application 은 adapter 에 의존하지 않는다.</li>
 *   <li>★ investment-service 는 order/settlement/loan/financial 패키지에 코드 의존 0
 *       — 재무제표는 HTTP, 재원은 Kafka 이벤트로만 받는다 (DB-per-service 경계).</li>
 * </ul>
 */
class InvestmentArchitectureTest {

    private static JavaClasses investmentClasses;

    @BeforeAll
    static void importClasses() {
        investmentClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel.investment");
    }

    @Test
    void 도메인은_application_과_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..investment.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..investment.application..", "..investment.adapter..")
                .allowEmptyShould(true);
        rule.check(investmentClasses);
    }

    @Test
    void application_은_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..investment.application..")
                .should().dependOnClassesThat()
                .resideInAPackage("..investment.adapter..")
                .allowEmptyShould(true);
        rule.check(investmentClasses);
    }

    @Test
    void investment_은_다른_도메인서비스에_코드의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.investment..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "github.lms.lemuel.order..",
                        "github.lms.lemuel.settlement..",
                        "github.lms.lemuel.loan..",
                        "github.lms.lemuel.financial..")
                .allowEmptyShould(true);
        rule.check(investmentClasses);
    }
}
