package github.lms.lemuel.reservation;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * reservation 모듈이 다른 비즈니스 도메인 패키지에 코드 의존하지 않음을 강제한다.
 *
 * <p>가장 강한 보장은 사실 Gradle 모듈 경계다 — reservation-service 는 order-service 를
 * 의존하지 않으므로 user/order/payment/product 클래스를 컴파일 단계에서 참조할 수 없다.
 * 이 ArchUnit 테스트는 그 의도를 명문화한다. (기존 HexagonalArchitectureTest 와 동일하게
 * 프로그래매틱 임포터 + allowEmptyShould 로 작성 — 툴체인 차이에 견고)
 */
class ReservationArchitectureTest {

    private static JavaClasses mainClasses;

    @BeforeAll
    static void importClasses() {
        mainClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption((Location location) ->
                        !location.contains("/generated/") && !location.contains("/build/generated"))
                .importPackages("github.lms.lemuel.reservation");
    }

    @Test
    void reservationShouldNotDependOnOtherBusinessDomains() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.reservation..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "github.lms.lemuel.user..",
                        "github.lms.lemuel.order..",
                        "github.lms.lemuel.payment..",
                        "github.lms.lemuel.product..",
                        "github.lms.lemuel.cart..",
                        "github.lms.lemuel.shipping..")
                .because("reservation 모듈은 타 도메인에 코드 의존하지 않는다 — 필요 시 포트(out)로 추상화")
                .allowEmptyShould(true);

        rule.check(mainClasses);
    }
}
