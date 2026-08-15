package github.lms.lemuel.board;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 헥사고날 의존 방향 강제.
 *
 * <p>문서로 적어 둔 경계는 지켜지지 않는다 — 컴파일이 잡아 주지 않기 때문이다. 도메인이 어댑터를
 * 한 번만 import 해도 "정책을 바꾸려면 JPA 를 알아야 하는" 구조로 미끄러진다.
 */
class BoardArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel.board");
    }

    @Test
    @DisplayName("도메인은 어댑터를 모른다")
    void domainDoesNotDependOnAdapter() {
        noClasses().that().resideInAPackage("..board.domain..")
                .should().dependOnClassesThat().resideInAPackage("..board.adapter..")
                .check(classes);
    }

    @Test
    @DisplayName("도메인은 응용 계층을 모른다 — 의존은 안에서 밖이 아니라 밖에서 안으로 흐른다")
    void domainDoesNotDependOnApplication() {
        noClasses().that().resideInAPackage("..board.domain..")
                .should().dependOnClassesThat().resideInAPackage("..board.application..")
                .check(classes);
    }

    @Test
    @DisplayName("도메인은 스프링·JPA 를 모른다 — 순수 POJO")
    void domainIsFrameworkFree() {
        noClasses().that().resideInAPackage("..board.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "jakarta.validation..")
                .check(classes);
    }

    @Test
    @DisplayName("응용 계층은 어댑터를 모른다 — 포트를 통해서만 바깥과 만난다")
    void applicationDoesNotDependOnAdapter() {
        noClasses().that().resideInAPackage("..board.application..")
                .should().dependOnClassesThat().resideInAPackage("..board.adapter..")
                .check(classes);
    }

    @Test
    @DisplayName("영속 어댑터는 웹 어댑터를 모른다 — 두 바깥은 서로를 몰라야 교체가 가능하다")
    void persistenceDoesNotDependOnWeb() {
        noClasses().that().resideInAPackage("..board.adapter.out..")
                .should().dependOnClassesThat().resideInAPackage("..board.adapter.in..")
                .check(classes);
    }
}
