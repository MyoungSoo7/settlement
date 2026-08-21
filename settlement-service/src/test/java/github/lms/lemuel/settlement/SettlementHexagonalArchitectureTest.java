package github.lms.lemuel.settlement;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * settlement-service 의 헥사고날 의존 방향 + MSA 코드 경계를 강제하는 가드 (account/loan 과 대칭).
 *
 * <p>settlement-service 는 다수의 바운디드 컨텍스트(settlement·payout·ledger·chargeback·
 * pgreconciliation·recovery·report·tax·integrity)를 담으므로, 컨텍스트별이 아니라 레이어 패키지
 * 세그먼트(`..domain..` / `..application..` / `..adapter..`)로 전 컨텍스트를 한 번에 강제한다.
 * shared-common(`..common..`)은 스코프에서 제외한다.
 *
 * <p>핵심 불변식:
 * <ul>
 *   <li>도메인은 application/adapter 에 의존하지 않는다 (의존 방향 — 헥사고날 핵심).</li>
 *   <li>application 은 adapter 에 의존하지 않는다 (포트 우회 금지).</li>
 *   <li>★ settlement-service 는 order/payment 패키지에 코드 의존 0
 *       — order 데이터는 Kafka 프로젝션(settlement_*_view) + /internal/recon 으로만 (ADR 0020, DB-per-service 경계).</li>
 * </ul>
 *
 * <p>(기존 {@link SettlementProjectionArchitectureTest} 와 동일하게 프로그래매틱 임포터 +
 * {@code allowEmptyShould} 로 작성 — 툴체인/바이트코드 버전 차이에 견고.)
 */
class SettlementHexagonalArchitectureTest {

    /** 임포트 건수 하한. settlement-service 는 현재 수백 개를 임포트한다. */
    private static final int MIN_IMPORTED_CLASSES = 100;

    private static JavaClasses settlementClasses;

    @BeforeAll
    static void importClasses() {
        settlementClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption((Location location) ->
                        !location.contains("/generated/") && !location.contains("/build/generated"))
                .importPackages("github.lms.lemuel");
    }

    /**
     * 임포트 건수 하한 — 이 클래스의 모든 규칙이 {@link #settlementClasses} 를 대상으로 하므로,
     * 임포트가 0개면 규칙 전부가 <b>공허 통과</b>한다(검사 대상 없이 green). green 과 blind 는 겉으로
     * 구분되지 않는다.
     *
     * <p>가상의 위험이 아니다 — order-service 는 ArchUnit 1.3.0 + Java 25(class major 69) 조합에서
     * 0개를 임포트한 채 규칙 전부가 green 이었고, {@code AccountArchitectureTest} 주석에도 같은 사고가
     * 남아 있다. {@code allowEmptyShould(true)} 는 그 상태를 <b>정상 통과로 보이게</b> 만든다.
     */
    @Test
    void importedClassesMustNotBeVacuous() {
        org.junit.jupiter.api.Assertions.assertTrue(settlementClasses.size() >= MIN_IMPORTED_CLASSES,
                "아키텍처 규칙의 검사 대상이 " + settlementClasses.size() + "개다 (기대 최소 "
                        + MIN_IMPORTED_CLASSES + "개). ArchUnit 이 현재 바이트코드 버전을 읽지 못하면 "
                        + "0개를 임포트하고 이 클래스의 모든 규칙이 공허 통과한다.");
    }

    @Test
    void 도메인은_application_과_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .and().resideOutsideOfPackage("github.lms.lemuel.common..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..application..", "..adapter..")
                .allowEmptyShould(true);
        rule.check(settlementClasses);
    }

    @Test
    void application_은_adapter_에_의존하지_않는다() {
        // (감사 MED-1 해소) 과거 out-port(SettlementReconciliationQueryPort·SettlementSearchQueryPort·
        // SettlementSummaryQueryPort)가 adapter.out.persistence.querydsl.dto 의 DTO 7종을 반환 타입으로
        // 참조하던 application→adapter 역의존 20건은, 해당 DTO 들을 application.port.out.dto 로 이전해 제거됨.
        // 이제 이 규칙이 실효 강제된다(account-service 대칭). ArchUnit 은 1.4.1 이라야 Java25 바이트코드를 파싱한다.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application..")
                .and().resideOutsideOfPackage("github.lms.lemuel.common..")
                .should().dependOnClassesThat()
                .resideInAPackage("..adapter..")
                .allowEmptyShould(true);
        rule.check(settlementClasses);
    }

    /**
     * 슬라이스는 다른 슬라이스의 <b>어댑터 내부</b>를 참조하지 않는다.
     *
     * <p>위의 {@code application_은_adapter_에_의존하지_않는다} 는 <b>같은 슬라이스 안의 세로 방향</b>만 본다.
     * 어댑터가 <b>다른 슬라이스의</b> 어댑터를 참조하는 가로 방향은 그 규칙을 통과한다 —
     * {@code ledger/adapter/out/persistence} 가 {@code settlement} 의 JPA 리포지토리·엔티티를 직접 읽어도
     * 레이어는 adapter→adapter 라 걸리지 않았다. 이 규칙이 그 사각지대를 덮는다.
     *
     * <p>남의 어댑터를 읽으면 그 슬라이스의 저장 스키마가 사실상 공개 API 가 되고, 도메인 규칙이
     * 조용히 복제된다 — {@code TaxSettlementViewPersistenceAdapter} 안에 아직 남아 있는
     * {@code Settlement.getImmediatePayoutAmount()} 사본이 그 흔적이다(그 클래스 주석에 통합을
     * 미룬 이유가 적혀 있다).
     *
     * <p>해법은 포트 경유가 아니라 <b>소유권 역전</b>이다 — 소비 슬라이스가 필요 인터페이스를 선언하고,
     * 데이터를 가진 슬라이스가 자기 어댑터로 그것을 구현한다.
     */
    @Test
    void 슬라이스는_다른_슬라이스의_어댑터_내부를_참조하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel..")
                .and().resideOutsideOfPackage("github.lms.lemuel.common..")
                .should(다른_슬라이스의_어댑터에_의존한다())
                .allowEmptyShould(true);
        rule.check(settlementClasses);
    }

    private static ArchCondition<JavaClass> 다른_슬라이스의_어댑터에_의존한다() {
        return new ArchCondition<>("다른 슬라이스의 adapter 패키지에 의존한다") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String from = sliceOf(item);
                if (from == null) {
                    return;
                }
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    String to = sliceOf(dependency.getTargetClass());
                    if (to == null || to.equals(from)) {
                        continue;
                    }
                    if (!dependency.getTargetClass().getPackageName()
                            .startsWith("github.lms.lemuel." + to + ".adapter")) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.satisfied(item, dependency.getDescription()));
                }
            }
        };
    }

    /** {@code github.lms.lemuel.<슬라이스>...} 의 첫 세그먼트. shared-common(common)과 루트 클래스는 제외. */
    private static String sliceOf(JavaClass javaClass) {
        String prefix = "github.lms.lemuel.";
        String packageName = javaClass.getPackageName();
        if (!packageName.startsWith(prefix)) {
            return null;
        }
        String rest = packageName.substring(prefix.length());
        int dot = rest.indexOf('.');
        String slice = dot < 0 ? rest : rest.substring(0, dot);
        return slice.isEmpty() || "common".equals(slice) ? null : slice;
    }

    /**
     * 슬라이스(= {@code github.lms.lemuel.<도메인>} 최상위 패키지) 사이에 순환 의존이 없다.
     *
     * <p>이 모듈은 순환 8건으로 시작해 마지막에 규칙을 켰다 — 슬라이스가 13개나 되는 유일한 모듈이라
     * 순환이 가장 깊게 쌓여 있었다. 두 단계로 걷어냈다:
     * <ol>
     *   <li>다른 슬라이스의 <b>어댑터 내부</b>를 읽던 경계 침범을 소유권 역전으로 정리 → 8 → 4
     *       ({@link #슬라이스는_다른_슬라이스의_어댑터_내부를_참조하지_않는다()} 가 재발을 막는다)</li>
     *   <li>남은 4건은 침범이 아니라 <b>방향</b>의 문제였다. payout·recovery 가 settlement 의 포트를 직접
     *       부르던 두 지점을 각자의 요구 포트 + settlement 쪽 브리지로 뒤집어 결합을 settlement→* 한 방향으로 모았다</li>
     * </ol>
     *
     * <p>임포트 공허 통과 방어는 {@link #importedClassesMustNotBeVacuous()} 가 같은 {@link #settlementClasses}
     * 에 대해 담당한다 — 임포트가 0개면 빈 그래프에 순환이 없어 이 규칙도 조용히 통과한다.
     */
    @Test
    void 슬라이스_사이에_순환_의존이_없다() {
        SlicesRuleDefinition.slices()
                .matching("github.lms.lemuel.(*)..")
                .should().beFreeOfCycles()
                .check(settlementClasses);
    }

    @Test
    void settlement_은_order_payment_에_코드의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel..")
                .and().resideOutsideOfPackage("github.lms.lemuel.common..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "github.lms.lemuel.order..",
                        "github.lms.lemuel.payment..")
                .allowEmptyShould(true);
        rule.check(settlementClasses);
    }
}
