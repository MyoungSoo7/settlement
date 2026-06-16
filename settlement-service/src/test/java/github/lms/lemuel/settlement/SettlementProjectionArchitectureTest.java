package github.lms.lemuel.settlement;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ADR 0020 Phase 3b-6 — read-model 컷오버 후 경계 가드.
 *
 * <p>settlement 는 order 테이블을 @Immutable read-model 로 직접 매핑하지 않는다. 모든 cross-domain
 * 데이터는 이벤트로 적재되는 로컬 프로젝션(settlement_*_view)으로만 읽는다.
 *
 * <p>(기존 HexagonalArchitectureTest 와 동일하게 프로그래매틱 임포터 + allowEmptyShould 로 작성 —
 * 툴체인/바이트코드 버전 차이에 견고.)
 */
class SettlementProjectionArchitectureTest {

    private static JavaClasses settlementClasses;

    @BeforeAll
    static void importClasses() {
        settlementClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption((Location location) ->
                        !location.contains("/generated/") && !location.contains("/build/generated"))
                .importPackages("github.lms.lemuel.settlement");
    }

    @Test
    void settlementShouldNotImmutablyMapForeignTables() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.settlement..")
                .should().beAnnotatedWith("org.hibernate.annotations.Immutable")
                .because("order 테이블을 @Immutable 로 직접 매핑하던 read-model 은 이벤트 기반 로컬 프로젝션으로 대체됨 (ADR 0020 Phase 3)")
                .allowEmptyShould(true);

        rule.check(settlementClasses);
    }

    @Test
    void noReadModelClassRemains() {
        ArchRule rule = classes()
                .that().resideInAPackage("github.lms.lemuel.settlement..")
                .should().haveSimpleNameNotEndingWith("ReadModel")
                .because("read-model 은 *View 프로젝션으로 컷오버됨 — 재도입 금지")
                .allowEmptyShould(true);

        rule.check(settlementClasses);
    }
}
