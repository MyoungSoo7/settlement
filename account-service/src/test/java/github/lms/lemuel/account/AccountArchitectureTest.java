package github.lms.lemuel.account;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * account-service 의 헥사고날 아키텍처 + MSA 코드 경계를 강제하는 가드.
 *
 * <ul>
 *   <li>도메인은 application/adapter 에 의존하지 않는다.</li>
 *   <li>application 은 adapter 에 의존하지 않는다.</li>
 *   <li>★ account-service 는 loan/settlement/order/investment 패키지에 코드 의존 0
 *       — 데이터는 Kafka 이벤트로만 수신한다 (DB-per-service 경계).</li>
 *   <li>수신 상품 서브도메인(account.banking.*)끼리는 서로의 내부를 참조하지 않는다.</li>
 * </ul>
 *
 * <p>레이어 규칙은 원장 코어와 {@code account.banking.<product>} 두 뿌리를 모두 대상으로 한다
 * — 아래 패키지 상수 주석 참조.
 */
class AccountArchitectureTest {

    // 레이어 패턴을 상수로 뽑는 이유: 계정계는 원장 코어(account.domain/application/adapter)와
    // 수신 상품 서브도메인(account.banking.<product>.domain/application/adapter) 두 뿌리를 갖는다.
    // 예전처럼 "..account.domain.." 만 쓰면 앞의 세그먼트가 account.domain 으로 연속돼야 해
    // account.banking.timedeposit.domain 은 조용히 매치되지 않는다 — 신규 서브도메인이 레이어 규칙
    // 밖에 놓여 위반이 검출되지 않던 갭. 두 뿌리를 모두 열거해 막는다.
    // (공용 "..domain.." 같은 느슨한 패턴은 common.audit.adapter 등 타 모듈까지 잡아 오탐이 난다.)
    private static final String[] DOMAIN_PACKAGES = {
            "github.lms.lemuel.account.domain..",
            "github.lms.lemuel.account.banking..domain.."
    };
    private static final String[] APPLICATION_PACKAGES = {
            "github.lms.lemuel.account.application..",
            "github.lms.lemuel.account.banking..application.."
    };
    private static final String[] ADAPTER_PACKAGES = {
            "github.lms.lemuel.account.adapter..",
            "github.lms.lemuel.account.banking..adapter.."
    };

    /** 수신 상품 서브도메인 — 상호 의존 금지 규칙과 임포트 가드가 같은 목록을 본다. */
    private static final String[] PRODUCTS = {"timedeposit", "savings", "pension"};

    private static String[] concat(String[] a, String[] b) {
        String[] merged = new String[a.length + b.length];
        System.arraycopy(a, 0, merged, 0, a.length);
        System.arraycopy(b, 0, merged, a.length, b.length);
        return merged;
    }

    private static JavaClasses accountClasses;

    @BeforeAll
    static void importClasses() {
        accountClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel.account");
    }

    /**
     * <b>이 테스트가 먼저 깨져야 나머지가 의미를 갖는다.</b> 아래 규칙은 전부
     * {@code allowEmptyShould(true)} 라 임포터가 클래스를 하나도 못 읽어도 조용히 초록이 된다 —
     * 실제로 ArchUnit 1.3.0 은 Java 25 클래스 파일(major 69)을 못 읽어 0개를 임포트하고도 통과했다.
     * 그래서 세 서브도메인이 실제로 임포트됐는지를 규칙보다 먼저 못 박는다.
     */
    @Test
    void 임포터가_수신_서브도메인_클래스를_실제로_읽었다() {
        assertThat(accountClasses).isNotEmpty();
        for (String product : PRODUCTS) {
            String root = "github.lms.lemuel.account.banking." + product;
            assertThat(accountClasses.stream().filter(c -> c.getPackageName().startsWith(root)).count())
                    .as("%s 하위에서 임포트된 클래스 수", root)
                    .isPositive();
        }
    }

    @Test
    void 도메인은_application_과_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(DOMAIN_PACKAGES)
                .should().dependOnClassesThat()
                .resideInAnyPackage(concat(APPLICATION_PACKAGES, ADAPTER_PACKAGES))
                .allowEmptyShould(true);
        rule.check(accountClasses);
    }

    @Test
    void application_은_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(APPLICATION_PACKAGES)
                .should().dependOnClassesThat()
                .resideInAnyPackage(ADAPTER_PACKAGES)
                .allowEmptyShould(true);
        rule.check(accountClasses);
    }

    @Test
    void 수신_서브도메인은_서로의_내부에_의존하지_않는다() {
        // 정기예금·적금·퇴직연금은 상품별로 독립 애그리거트다. 서로를 직접 참조하기 시작하면
        // "적금 해지가 예금 도메인을 건드리는" 식의 결합이 생겨 각 상품의 불변식을 따로 검증할 수 없게 된다.
        // 공통이 필요하면 원장 코어(account.domain 의 AccountEntry/GlAccount)를 경유한다.
        for (String product : PRODUCTS) {
            String self = "github.lms.lemuel.account.banking." + product + "..";
            String[] others = java.util.Arrays.stream(PRODUCTS)
                    .filter(p -> !p.equals(product))
                    .map(p -> "github.lms.lemuel.account.banking." + p + "..")
                    .toArray(String[]::new);
            ArchRule rule = noClasses()
                    .that().resideInAPackage(self)
                    .should().dependOnClassesThat().resideInAnyPackage(others)
                    .allowEmptyShould(true);
            rule.check(accountClasses);
        }
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
        // (KafkaTemplate 은 DLT 격리 전용으로만 쓰이므로 제외 — 공용 KafkaConsumerErrorHandlingConfig 참조.
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

    @Test
    void account_는_KafkaTemplate_send_를_직접_호출하지_않는다() {
        // 소비 전용 경계의 텍스트-매칭 우회(변수명 리네이밍·다른 식별자로 producer.send(...))를 타입 기반으로 차단
        // — guard grep(`kafkaTemplate.send`)의 사각을 메운다(감사 MED-4). account 자기 코드가 KafkaTemplate.send(..)를
        // '직접' 호출하면 비즈니스 이벤트 발행으로 간주해 하드스톱한다.
        // DLT 격리는 DeadLetterPublishingRecoverer(프레임워크)가 send 를 내부 호출하므로 account 코드엔 직접 호출이 없다
        // → 정상 DLT 배선(공용 KafkaConsumerErrorHandlingConfig)을 false-positive 없이 통과한다.
        DescribedPredicate<JavaMethodCall> callsKafkaTemplateSend =
                new DescribedPredicate<>("KafkaTemplate.send(..) 를 직접 호출") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        return "send".equals(call.getTarget().getName())
                                && "org.springframework.kafka.core.KafkaTemplate"
                                        .equals(call.getTarget().getOwner().getName());
                    }
                };
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.account..")
                .should().callMethodWhere(callsKafkaTemplateSend)
                .allowEmptyShould(true);
        rule.check(accountClasses);
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
