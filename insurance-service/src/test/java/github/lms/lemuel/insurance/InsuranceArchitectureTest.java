package github.lms.lemuel.insurance;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
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

    /**
     * 슬라이스(= {@code github.lms.lemuel.<도메인>} 최상위 패키지) 사이에 순환 의존이 없다.
     *
     * <p>이 모듈은 현재 슬라이스가 1개뿐이라 규칙은 자명하게 통과한다. 그럼에도 켜 두는 이유는
     * <b>두 번째 최상위 도메인 패키지가 추가되는 순간</b>부터 순환을 차단하기 위해서다
     * (settlement-service 8건 · order-service 1건이 그렇게 쌓였다).
     *
     * <p>임포트 범위를 모듈 패키지가 아니라 {@code github.lms.lemuel} 로 넓히는 것이 핵심이다 —
     * 모듈 패키지로 좁히면 새로 생긴 형제 패키지가 애초에 임포트되지 않아 규칙이 못 본다.
     */
    @Test
    void 슬라이스_사이에_순환_의존이_없다() {
        JavaClasses lemuelClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel");
        // 임포터가 0개를 읽으면 순환 규칙은 조용히 통과한다(가짜 GREEN). 규칙보다 먼저 못 박는다.
        if (lemuelClasses.stream().findAny().isEmpty()) {
            throw new AssertionError("ArchUnit 임포터가 클래스를 0개 읽었다 — 순환 규칙이 무력화된다");
        }
        SlicesRuleDefinition.slices()
                .matching("github.lms.lemuel.(*)..")
                .should().beFreeOfCycles()
                .check(lemuelClasses);
    }
}
