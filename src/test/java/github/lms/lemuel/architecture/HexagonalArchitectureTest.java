package github.lms.lemuel.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 헥사고날 아키텍처 경계 규칙.
 *
 * 일부 규칙은 현재 위반되는 코드가 있어 임시 허용 목록(exclude)을 두고 있다.
 * 허용 목록에 있는 파일들은 별도의 리팩터 태스크로 정리해야 한다.
 */
class HexagonalArchitectureTest {

    private static JavaClasses mainClasses;

    @BeforeAll
    static void importClasses() {
        mainClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption((Location location) ->
                        !location.contains("/generated/") && !location.contains("/build/generated"))
                .importPackages("github.lms.lemuel");
    }

    /**
     * 도메인 레이어(..domain..)는 Spring/JPA/프레임워크 의존성을 가지지 않아야 한다.
     * adapter·application 하위의 `.domain` 유사 패키지는 제외.
     */
    @Test
    void domainShouldNotDependOnSpringOrJpa() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .and().resideOutsideOfPackage("..adapter..")
                .and().resideOutsideOfPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "javax.persistence..")
                .because("도메인 레이어는 프레임워크에 의존하지 않는 순수 POJO 여야 한다")
                .allowEmptyShould(true);

        rule.check(mainClasses);
    }

    /**
     * 애플리케이션 서비스는 JPA 리포지토리/어댑터 구현체에 직접 의존하지 않는다.
     *
     * 현재 허용 예외: EcommerceCategoryService, ProductImageService — 향후 포트/어댑터로 분리할 리팩터 대상.
     */
    @Test
    void applicationServiceShouldNotUseJpaRepositoryDirectly() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application.service..")
                .should().dependOnClassesThat().resideInAPackage("..adapter.out.persistence..")
                .because("애플리케이션 서비스는 어댑터(JPA)에 직접 의존하지 않고 포트를 사용해야 한다")
                .allowEmptyShould(true);

        rule.check(mainClasses);
    }

    /**
     * 어댑터는 타 도메인의 JPA 엔티티/리포지토리를 직접 import 하지 않는다.
     *
     * 허용 예외(CQRS 읽기 모델/성능 지향 집계):
     *   - SettlementSearchDocumentMapper: Elasticsearch 인덱싱용 read-model
     *   - SettlementQueryRepositoryImpl: QueryDSL 크로스 엔티티 조인
     *   - CapturedPaymentsAdapter: 정산 생성 시 결제 데이터 읽기
     */
    @Test
    void adaptersShouldNotDirectlyReferenceOtherDomainsPersistence() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..adapter..")
                .and().doNotHaveSimpleName("SettlementSearchDocumentMapper")
                .and().doNotHaveSimpleName("SettlementQueryRepositoryImpl")
                .and().doNotHaveSimpleName("CapturedPaymentsAdapter")
                .should(com.tngtech.archunit.lang.conditions.ArchConditions.dependOnClassesThat(
                        crossDomainPersistence()))
                .because("어댑터는 타 도메인의 JPA 엔티티/리포지토리를 직접 import 하지 않는다. "
                        + "CQRS 읽기/집계 전용 클래스는 명시적 허용 목록으로 관리")
                .allowEmptyShould(true);

        rule.check(mainClasses);
    }

    private static com.tngtech.archunit.base.DescribedPredicate<JavaClass> crossDomainPersistence() {
        return new com.tngtech.archunit.base.DescribedPredicate<>(
                "타 도메인의 adapter.out.persistence 클래스") {
            @Override
            public boolean test(JavaClass target) {
                String pkg = target.getPackageName();
                return pkg.startsWith("github.lms.lemuel.")
                        && pkg.contains(".adapter.out.persistence");
            }
        };
    }

    /**
     * application.port.* 의 *Port 는 인터페이스여야 한다.
     */
    @Test
    void portsShouldBeInterfaces() {
        ArchRule rule = classes()
                .that().resideInAPackage("..application.port..")
                .and().haveSimpleNameEndingWith("Port")
                .should().beInterfaces()
                .because("포트는 어댑터가 구현하는 계약 인터페이스")
                .allowEmptyShould(true);

        rule.check(mainClasses);
    }
}
