package github.lms.lemuel.account;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * account-service 의 헥사고날 아키텍처 + MSA 코드 경계를 강제하는 가드.
 *
 * <ul>
 *   <li>도메인은 application/adapter 에 의존하지 않는다.</li>
 *   <li>application 은 adapter 에 의존하지 않는다.</li>
 *   <li>★ account-service 는 loan/settlement/order/investment 패키지에 코드 의존 0
 *       — 데이터는 Kafka 이벤트로만 수신한다 (DB-per-service 경계).</li>
 * </ul>
 */
class AccountArchitectureTest {

    private static JavaClasses accountClasses;

    @BeforeAll
    static void importClasses() {
        accountClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel.account");
    }

    @Test
    void 도메인은_application_과_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..account.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..account.application..", "..account.adapter..")
                .allowEmptyShould(true);
        rule.check(accountClasses);
    }

    @Test
    void application_은_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..account.application..")
                .should().dependOnClassesThat()
                .resideInAPackage("..account.adapter..")
                .allowEmptyShould(true);
        rule.check(accountClasses);
    }

    @Test
    void account_는_타서비스_도메인패키지에_코드의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.account..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "github.lms.lemuel.order..",
                        "github.lms.lemuel.settlement..",
                        "github.lms.lemuel.loan..",
                        "github.lms.lemuel.investment..")
                .allowEmptyShould(true);
        rule.check(accountClasses);
    }

    @Test
    void account_는_소비전용이라_Outbox_발행머시너리에_의존하지_않는다() {
        // 이벤트 발행(Outbox) 금지 — payout.completed 등 발행은 settlement 만(ADR 0026 Option A).
        // (KafkaTemplate 은 DLT 격리 전용으로만 쓰이므로 제외 — KafkaErrorHandlerConfig 참조.
        //  비즈니스 이벤트 발행 경로인 Outbox 저장/발행 포트 의존만 하드스톱한다.)
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.account..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("github.lms.lemuel.common.outbox.application.port.out.PublishExternalEventPort")
                .allowEmptyShould(true);
        rule.check(accountClasses);
    }
}
