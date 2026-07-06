package github.lms.lemuel.financial.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 헥사고날 의존 방향 강제 — 도메인은 순수 POJO, 애플리케이션은 어댑터를 모른다.
 */
@AnalyzeClasses(packages = "github.lms.lemuel.financial")
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage("..financial.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "com.fasterxml.jackson..");

    @ArchTest
    static final ArchRule domainDoesNotDependOutward = noClasses()
            .that().resideInAPackage("..financial.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..financial.application..", "..financial.adapter..", "..financial.config..");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnAdapters = noClasses()
            .that().resideInAPackage("..financial.application..")
            .should().dependOnClassesThat().resideInAnyPackage("..financial.adapter..");
}
