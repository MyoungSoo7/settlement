package github.lms.lemuel.loan;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * loan-service 의 헥사고날 아키텍처 + MSA 코드 경계를 강제하는 가드.
 *
 * <p>핵심 불변식:
 * <ul>
 *   <li>도메인은 application/adapter 에 의존하지 않는다 (의존 방향).</li>
 *   <li>application 은 adapter 에 의존하지 않는다.</li>
 *   <li>★ loan-service 는 order/settlement 패키지에 코드 의존 0
 *       — 정산 데이터는 Kafka 이벤트로만 수신한다 (DB-per-service 경계).</li>
 *   <li>★ 인바운드 포트는 반드시 어떤 인바운드 어댑터에서 호출된다 (도달 가능성).</li>
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
    void loan_은_order_settlement_에_코드의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.loan..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "github.lms.lemuel.order..",
                        "github.lms.lemuel.settlement..")
                .allowEmptyShould(true);
        rule.check(loanClasses);
    }

    /**
     * 인바운드 포트는 <b>반드시 도달 가능해야</b> 한다 — 구현체와 단위 테스트가 있어도 어떤 인바운드
     * 어댑터(REST·Kafka·스케줄러)도 호출하지 않으면 그 기능은 런타임에 존재하지 않는다.
     *
     * <p>실제로 담보 재평가·강제집행(마진콜 140%·청산 120% 판정)이 이 상태였다: 정책 상수도 서비스도
     * 초록불 단위 테스트도 있는데 트리거가 없어, 담보 가치가 반토막 나도 아무 일도 일어나지 않았다.
     * 단위 테스트는 "호출하는 사람이 없다"를 볼 수 없으므로 구조 가드가 대신 본다.
     */
    @Test
    void 모든_인바운드_포트는_어댑터에서_호출된다() {
        ArchRule rule = classes()
                .that().resideInAPackage("..loan.application.port.in..")
                .and().areInterfaces()
                .should(reachableFromInboundAdapter())
                .allowEmptyShould(true);
        rule.check(loanClasses);
    }

    private static ArchCondition<JavaClass> reachableFromInboundAdapter() {
        return new ArchCondition<>("인바운드 어댑터에서 호출되어야 한다") {
            @Override
            public void check(JavaClass port, ConditionEvents events) {
                boolean reachable = port.getDirectDependenciesToSelf().stream()
                        .anyMatch(dep -> dep.getOriginClass().getPackageName().contains(".loan.adapter.in."));
                if (!reachable) {
                    events.add(SimpleConditionEvent.violated(port,
                            port.getName() + " 를 호출하는 인바운드 어댑터가 없다 "
                                    + "— 구현돼 있어도 런타임에서 도달할 수 없다"));
                }
            }
        };
    }
}
