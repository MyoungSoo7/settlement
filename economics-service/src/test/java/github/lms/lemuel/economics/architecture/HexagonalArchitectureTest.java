package github.lms.lemuel.economics.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 헥사고날 의존 방향 강제 — 도메인은 순수 POJO, 애플리케이션은 어댑터를 모른다.
 */
@AnalyzeClasses(packages = "github.lms.lemuel.economics")
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage("..economics.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "com.fasterxml.jackson..");

    @ArchTest
    static final ArchRule domainDoesNotDependOutward = noClasses()
            .that().resideInAPackage("..economics.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..economics.application..", "..economics.adapter..", "..economics.config..");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnAdapters = noClasses()
            .that().resideInAPackage("..economics.application..")
            .should().dependOnClassesThat().resideInAnyPackage("..economics.adapter..");
}
