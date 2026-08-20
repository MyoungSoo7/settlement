package github.lms.lemuel.commondata.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 헥사고날 의존 방향 강제 — 도메인은 순수 POJO, 애플리케이션은 어댑터를 모른다.
 */
@AnalyzeClasses(packages = "github.lms.lemuel")
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage("..commondata.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "com.fasterxml.jackson..");

    @ArchTest
    static final ArchRule domainDoesNotDependOutward = noClasses()
            .that().resideInAPackage("..commondata.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..commondata.application..", "..commondata.adapter..", "..commondata.config..");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnAdapters = noClasses()
            .that().resideInAPackage("..commondata.application..")
            .should().dependOnClassesThat().resideInAnyPackage("..commondata.adapter..");

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
    @ArchTest
    static void 슬라이스_사이에_순환_의존이_없다(JavaClasses lemuelClasses) {
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
