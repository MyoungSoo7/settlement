package github.lms.lemuel.card.application.service;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 사후 지출관리 워크플로 ↔ 실시간 승인 유스케이스 완전 비결합 검증.
 *
 * <h3>왜 이 테스트가 필요한가</h3>
 * <ul>
 *   <li>승인 경로({@code AuthorizeCardService})가 지출보고서 상태({@code ExpenseReportStatus})에
 *       의존하면 승인 p99 가 지출 관리 처리 시간에 끌린다.</li>
 *   <li>지출 워크플로가 승인 홀드({@code AuthorizationHold})를 직접 참조하면 경계가 무너져
 *       향후 지출 관리 모듈 독립 배포가 불가능해진다.</li>
 *   <li>두 방향 모두 ArchUnit 으로 빌드 타임에 강제한다 — 코드 리뷰에서 놓쳐도 CI 에서 잡힌다.</li>
 * </ul>
 *
 * <h3>DeclineReason 불변식</h3>
 * 승인 거절 사유({@code DeclineReason})에 {@code PENDING_APPROVAL} 같은 지출 워크플로 개념이
 * 들어오면 이 비결합 원칙이 이미 깨진 것이다 — 이 테스트가 그 신호이기도 하다.
 */
class ExpenseWorkflowDecouplingTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel.card");
    }

    @Test
    @DisplayName("승인 서비스(AuthorizeCardService)는 지출보고서 도메인에 의존하지 않는다")
    void authorizeCardService_doesNotDependOnExpenseWorkflow() {
        ArchRule rule = noClasses()
                .that().haveFullyQualifiedName(
                        "github.lms.lemuel.card.application.service.AuthorizeCardService")
                .should().dependOnClassesThat()
                .resideInAPackage("github.lms.lemuel.card.domain..")
                .andShould().dependOnClassesThat()
                .haveNameMatching("ExpenseReport.*")
                .allowEmptyShould(true);
        // 실제로 검증할 규칙: AuthorizeCardService 가 ExpenseReport* 를 참조하지 않는다
        ArchRule noExpenseDependency = noClasses()
                .that().haveFullyQualifiedName(
                        "github.lms.lemuel.card.application.service.AuthorizeCardService")
                .should().dependOnClassesThat()
                .haveNameMatching(".*ExpenseReport.*")
                .allowEmptyShould(true);
        noExpenseDependency.check(classes);
    }

    @Test
    @DisplayName("지출보고서 서비스(ExpenseWorkflowService)는 승인 서비스에 의존하지 않는다")
    void expenseWorkflowService_doesNotDependOnAuthorizationService() {
        ArchRule rule = noClasses()
                .that().haveFullyQualifiedName(
                        "github.lms.lemuel.card.application.service.ExpenseWorkflowService")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName(
                        "github.lms.lemuel.card.application.service.AuthorizeCardService")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    @DisplayName("지출보고서 서비스는 AuthorizeCardUseCase 포트에도 의존하지 않는다")
    void expenseWorkflowService_doesNotDependOnAuthorizeCardUseCase() {
        ArchRule rule = noClasses()
                .that().haveFullyQualifiedName(
                        "github.lms.lemuel.card.application.service.ExpenseWorkflowService")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName(
                        "github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    @DisplayName("DeclineReason 에 PENDING_APPROVAL 이 없다 — 승인 경로에 워크플로 개념 혼입 금지")
    void declineReason_doesNotContainPendingApproval() {
        // DeclineReason 은 정확히 4개 값만 허용 (LIMIT_EXCEEDED, CARD_SUSPENDED, MEMBER_INACTIVE, MERCHANT_POLICY_VIOLATION)
        // PENDING_APPROVAL 추가 시 이 테스트가 깨지도록 리플렉션 검사
        Class<?> declineReasonClass;
        try {
            declineReasonClass = Class.forName("github.lms.lemuel.card.domain.DeclineReason");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("DeclineReason 클래스를 찾을 수 없습니다", e);
        }

        Object[] constants = declineReasonClass.getEnumConstants();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Object c : constants) {
            names.add(c.toString());
        }

        org.assertj.core.api.Assertions.assertThat(names)
                .as("DeclineReason 에 워크플로 개념(PENDING_APPROVAL, DELINQUENT 등)이 없어야 한다")
                .doesNotContain("PENDING_APPROVAL", "DELINQUENT", "EXPENSE_PENDING");

        org.assertj.core.api.Assertions.assertThat(names)
                .as("DeclineReason 정확히 4가지 값")
                .containsExactlyInAnyOrder(
                        "LIMIT_EXCEEDED", "CARD_SUSPENDED",
                        "MEMBER_INACTIVE", "MERCHANT_POLICY_VIOLATION");
    }

    @Test
    @DisplayName("지출보고서 도메인은 AuthorizationHold 를 직접 참조하지 않는다")
    void expenseReport_doesNotDirectlyReferenceAuthorizationHold() {
        ArchRule rule = noClasses()
                .that().haveFullyQualifiedName(
                        "github.lms.lemuel.card.domain.ExpenseReport")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName(
                        "github.lms.lemuel.card.domain.AuthorizationHold")
                .allowEmptyShould(true);
        rule.check(classes);
    }
}
