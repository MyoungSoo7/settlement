package github.lms.lemuel.loan;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * loan-service 의 헥사고날 아키텍처 + MSA 코드 경계를 강제하는 가드.
 *
 * <p>핵심 불변식:
 * <ul>
 *   <li>도메인은 application/adapter 에 의존하지 않는다 (의존 방향).</li>
 *   <li>application 은 adapter 에 의존하지 않는다.</li>
 *   <li>★ loan-service 는 order/settlement/reservation 패키지에 코드 의존 0
 *       — 정산 데이터는 Kafka 이벤트로만 수신한다 (DB-per-service 경계).</li>
 * </ul>
 *
 * <p>아직 loan 패키지에 클래스가 없을 수 있으므로 {@code allowEmptyShould(true)} 로
 * 빈 상태에서도 안정적으로 통과시키되, 코드가 추가되면 즉시 가드로 작동한다.
 */
class LoanArchitectureTest {

    private static JavaClasses loanClasses;

    @BeforeAll
    static void importClasses() {
        loanClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel.loan");
    }

    @Test
    void 도메인은_application_과_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..loan.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..loan.application..", "..loan.adapter..")
                .allowEmptyShould(true);
        rule.check(loanClasses);
    }

    @Test
    void application_은_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..loan.application..")
                .should().dependOnClassesThat()
                .resideInAPackage("..loan.adapter..")
                .allowEmptyShould(true);
        rule.check(loanClasses);
    }

    @Test
    void loan_은_order_settlement_reservation_에_코드의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.loan..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "github.lms.lemuel.order..",
                        "github.lms.lemuel.settlement..",
                        "github.lms.lemuel.reservation..")
                .allowEmptyShould(true);
        rule.check(loanClasses);
    }
}
