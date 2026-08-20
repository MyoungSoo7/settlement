package github.lms.lemuel.ai;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ai-service 의 헥사고날 아키텍처 + MSA 코드 경계 + <b>Spring AI 격리</b> 가드 (설계 §9).
 *
 * <p>핵심 불변식:
 * <ul>
 *   <li>도메인은 application/adapter/config 에 의존하지 않는다.</li>
 *   <li>application 은 adapter 에 의존하지 않는다 (config 의 AiChatProperties 주입은 허용 —
 *       config 는 조립 계층이지 adapter 가 아니다. operation 선례).</li>
 *   <li>★ Spring AI 타입({@code org.springframework.ai..})은 adapter.out.llm 밖에서 금지
 *       — 벤더/프레임워크 교체가 계약 변경 없이 가능해야 한다.</li>
 *   <li>★ ai-service 는 타 서비스 패키지에 코드 의존 0 — Phase 2 연동도 REST(내부 API)로만.</li>
 * </ul>
 */
class AiArchitectureTest {

    private static JavaClasses aiClasses;

    @BeforeAll
    static void importClasses() {
        aiClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel.ai");
    }

    @Test
    void 도메인은_application_과_adapter_와_config_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..ai..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..ai..application..", "..ai..adapter..", "..ai.config..")
                .allowEmptyShould(true);
        rule.check(aiClasses);
    }

    @Test
    void 도메인은_프레임워크_애노테이션에_의존하지_않는다() {
        // 도메인 POJO 에 JPA(@Entity/@Column)·Jackson(@JsonProperty)·Spring 이 새어들면
        // 순수성이 깨진다 — 의존 방향뿐 아니라 프레임워크 오염도 가드한다(회귀 방지).
        ArchRule rule = noClasses()
                .that().resideInAPackage("..ai..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("jakarta.persistence..", "com.fasterxml.jackson..", "org.springframework..")
                .allowEmptyShould(true);
        rule.check(aiClasses);
    }

    @Test
    void application_은_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..ai..application..")
                .should().dependOnClassesThat()
                .resideInAPackage("..ai..adapter..")
                .allowEmptyShould(true);
        rule.check(aiClasses);
    }

    @Test
    void springAI_는_adapter_out_llm_밖에서_사용할_수_없다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.ai..")
                .and().resideOutsideOfPackage("..ai.chat.adapter.out.llm..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework.ai..")
                .allowEmptyShould(true);
        rule.check(aiClasses);
    }

    @Test
    void ai_는_타_서비스에_코드의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.ai..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "github.lms.lemuel.order..",
                        "github.lms.lemuel.settlement..",
                        "github.lms.lemuel.loan..",
                        "github.lms.lemuel.financial..",
                        "github.lms.lemuel.economics..",
                        "github.lms.lemuel.company..",
                        "github.lms.lemuel.market..",
                        "github.lms.lemuel.operation..")
                .allowEmptyShould(true);
        rule.check(aiClasses);
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
