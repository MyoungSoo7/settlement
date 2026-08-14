package github.lms.lemuel.insurance;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * insurance-service 의 헥사고날 경계 + MSA 코드 경계 가드.
 *
 * <p>4가지 규칙을 강제한다:
 * <ol>
 *   <li>domain → application/adapter/config 의존 금지 (도메인 순수성)</li>
 *   <li>application → adapter 의존 금지 (포트-어댑터 방향)</li>
 *   <li>insurance → 타 서비스(order/settlement/loan/investment/account/organization/card) 코드 의존 금지</li>
 *   <li>domain → jakarta.persistence / org.springframework 의존 금지 (도메인 프레임워크 독립)</li>
 * </ol>
 *
 * <p>예외 화이트리스트 추가 없음. 모든 규칙은 {@code allowEmptyShould(true)} 로 빈 패키지에서도 통과.
 */
class InsuranceArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel.insurance");
    }

    @Test
    void 도메인은_application_adapter_config_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..insurance.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "..insurance.application..",
                        "..insurance.adapter..",
                        "..insurance.config..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void application_은_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..insurance.application..")
                .should().dependOnClassesThat()
                .resideInAPackage("..insurance.adapter..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void insurance_는_타_서비스_도메인에_코드의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.insurance..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "github.lms.lemuel.order..",
                        "github.lms.lemuel.settlement..",
                        "github.lms.lemuel.loan..",
                        "github.lms.lemuel.investment..",
                        "github.lms.lemuel.account..",
                        "github.lms.lemuel.organization..",
                        "github.lms.lemuel.card..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void 도메인은_JPA_와_Spring_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..insurance.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("jakarta.persistence..", "org.springframework..")
                .allowEmptyShould(true);
        rule.check(classes);
    }
}
