package github.lms.lemuel.education;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * education-service 의 헥사고날 경계 + MSA 코드 경계 가드 (deposit/card/organization 패턴).
 *
 * <p>education 은 18개 서비스 중 <b>유일하게 아키텍처 테스트가 없던 모듈</b>이었고, 실제로
 * 저장소 전체에서 유일한 "포트가 어댑터를 의존하는" 위반이 여기서 나왔다. 규칙이 지켜진 게 아니라
 * 검사가 있는 곳에서만 지켜졌다는 뜻이라, 본 수정보다 가드를 먼저 세운다.
 *
 * <p><b>동결(래칫) 정책</b> — 기존 위반 41건은 {@code src/test/resources/archunit_store} 에 목록으로
 * 커밋해 드러내고, <b>새 위반은 즉시 FAIL</b> 시킨다. 위반을 갚으면 저장소에서 자동으로 빠져 래칫이
 * 조여진다(되돌리려면 다시 FAIL 이므로 후퇴가 불가능하다). 동결 없이 red 로 두면 필수 CI
 * {@code Backend - Build/Test} 가 상시 실패해 main 으로 가는 모든 PR 이 막힌다.
 */
class EducationArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel.education");
    }

    @Test
    void 도메인은_application_adapter_config_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..education.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..education.application..", "..education.adapter..", "..education.config..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void 도메인은_JPA_와_Spring_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..education.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("jakarta.persistence..", "org.springframework..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    /**
     * ⚠️ 동결된 규칙 — 현재 40건 위반(기술부채)이 저장소에 기록돼 있다.
     *
     * <p>{@code CourseAdminService}/{@code LessonAdminService} 가 도메인 {@code Course}/{@code Lesson}
     * 대신 {@code *JpaEntity}·{@code *Repository} 를 직접 다루는 이중 모델 상태다. 갚는 작업은
     * 리포지토리를 {@code application/port/out} 포트로 승격하는 별도 커밋으로 진행한다.
     */
    @Test
    void application_은_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..education.application..")
                .should().dependOnClassesThat()
                .resideInAPackage("..education.adapter..")
                .allowEmptyShould(true);
        FreezingArchRule.freeze(rule).check(classes);
    }

    /**
     * 포트는 의도만 담고 기술을 담지 않는다.
     *
     * <p>포트 시그니처에 JPA 엔티티·{@code Pageable}·서블릿·Kafka 타입이 올라오면 코드 의존성이
     * 안에서 밖으로 뒤집히고, 어댑터를 갈아끼울 때 애플리케이션까지 따라 바뀐다.
     *
     * <p>⚠️ 동결된 규칙 — 현재 1건 위반({@code PublishEducationEventPort.coursePublished} 가
     * {@code CourseJpaEntity} 를 받는다). 저장소 전체 626개 포트 인터페이스 중 유일한 위반이다.
     */
    @Test
    void 포트는_기술_타입을_시그니처에_노출하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..education.application.port..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "..education.adapter..",
                        "jakarta.persistence..",
                        "jakarta.servlet..",
                        "org.springframework..",
                        "org.apache.kafka..",
                        "com.fasterxml.jackson..")
                .allowEmptyShould(true);
        FreezingArchRule.freeze(rule).check(classes);
    }

    @Test
    void education_은_타_서비스_도메인에_코드의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.education..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "github.lms.lemuel.order..",
                        "github.lms.lemuel.settlement..",
                        "github.lms.lemuel.card..",
                        "github.lms.lemuel.loan..",
                        "github.lms.lemuel.investment..",
                        "github.lms.lemuel.account..",
                        "github.lms.lemuel.organization..",
                        "github.lms.lemuel.deposit..",
                        "github.lms.lemuel.company..")
                .allowEmptyShould(true);
        rule.check(classes);
    }
}
