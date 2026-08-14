package github.lms.lemuel.deposit;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * deposit-service 의 헥사고날 경계 + MSA 코드 경계 가드 (card/organization/investment 패턴).
 */
class DepositArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel.deposit");
    }

    @Test
    void 도메인은_application_adapter_config_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..deposit.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..deposit.application..", "..deposit.adapter..", "..deposit.config..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void application_은_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..deposit.application..")
                .should().dependOnClassesThat()
                .resideInAPackage("..deposit.adapter..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void deposit_는_타_서비스_도메인에_코드의존하지_않는다() {
        // build.gradle.kts 의 명시 선언대로 settlement/card/account 코드·DB 직접 의존 0 이 핵심 —
        // 그 외 order/organization/investment/loan/company 도 같은 이유(코드 결합 금지)로 함께 가드한다.
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.deposit..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "github.lms.lemuel.order..",
                        "github.lms.lemuel.settlement..",
                        "github.lms.lemuel.card..",
                        "github.lms.lemuel.loan..",
                        "github.lms.lemuel.investment..",
                        "github.lms.lemuel.account..",
                        "github.lms.lemuel.organization..",
                        "github.lms.lemuel.company..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void 도메인은_JPA_와_Spring_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..deposit.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("jakarta.persistence..", "org.springframework..")
                .allowEmptyShould(true);
        rule.check(classes);
    }
}
