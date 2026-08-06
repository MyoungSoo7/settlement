package github.lms.lemuel.insurance.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CommissionSchedule 재구성(rehydrate) 계약 테스트.
 *
 * <p>영속성 어댑터는 DB 행을 이 빌더로 도메인 객체로 되살린다. 지급 완료(paidAt/paidAmount)
 * 같은 "저장된 뒤에만 존재하는" 상태까지 손실 없이 복원되어야 하므로, 전 필드 왕복을 못박는다.
 * setter 가 없는 애그리거트이므로 빌더가 유일한 재구성 경로다.
 */
@DisplayName("CommissionSchedule 재구성 계약")
class CommissionScheduleTest {

    /**
     * 전 필드 왕복 — DB 에서 읽은 지급 완료 행을 손실 없이 복원한다.
     */
    @Test
    @DisplayName("빌더로 전 필드를 손실 없이 재구성한다")
    void builder_rehydratesEveryFieldWithoutLoss() {
        // Arrange — 이미 지급까지 끝난 6회차 행을 가정
        LocalDate dueDate = LocalDate.of(2026, 3, 10);
        LocalDate paidAt = LocalDate.of(2026, 3, 11);

        // Act
        CommissionSchedule schedule = CommissionSchedule.builder()
                .id(42L)
                .commissionId("commission-uuid-6")
                .policyId("policy-uuid-1")
                .fcId("fc-100")
                .recipientType(CommissionConstants.RECIPIENT_TYPE_FC)
                .parentCommissionId("parent-commission-uuid")
                .installmentNo(6)
                .installmentAmount(new BigDecimal("8333.33"))
                .firstYearTotal(new BigDecimal("100000.00"))
                .dueDate(dueDate)
                .paidAt(paidAt)
                .paidAmount(new BigDecimal("8333.33"))
                .status("PAID")
                .build();

        // Assert
        assertThat(schedule.getId()).isEqualTo(42L);
        assertThat(schedule.getCommissionId()).isEqualTo("commission-uuid-6");
        assertThat(schedule.getPolicyId()).isEqualTo("policy-uuid-1");
        assertThat(schedule.getFcId()).isEqualTo("fc-100");
        assertThat(schedule.getRecipientType()).isEqualTo("FC");
        assertThat(schedule.getParentCommissionId()).isEqualTo("parent-commission-uuid");
        assertThat(schedule.getInstallmentNo()).isEqualTo(6);
        assertThat(schedule.getInstallmentAmount()).isEqualTo(new BigDecimal("8333.33"));
        assertThat(schedule.getFirstYearTotal()).isEqualTo(new BigDecimal("100000.00"));
        assertThat(schedule.getDueDate()).isEqualTo(dueDate);
        assertThat(schedule.getPaidAt()).isEqualTo(paidAt);
        assertThat(schedule.getPaidAmount()).isEqualTo(new BigDecimal("8333.33"));
        assertThat(schedule.getStatus()).isEqualTo("PAID");
    }

    /**
     * D5 — 계층 수수료 컬럼은 nullable. 신규 생성 행은 상위 행이 없다.
     */
    @Test
    @DisplayName("D5: recipientType·parentCommissionId 는 null 을 허용한다")
    void builder_allowsNullHierarchyColumns() {
        CommissionSchedule schedule = newMinimalBuilder().build();

        assertThat(schedule.getRecipientType()).isNull();
        assertThat(schedule.getParentCommissionId()).isNull();
        assertThat(schedule.getId()).isNull();
        assertThat(schedule.getDueDate()).isNull();
        assertThat(schedule.getPaidAt()).isNull();
        assertThat(schedule.getPaidAmount()).isNull();
    }

    /**
     * 상태 기본값 — 미지정 시 SCHEDULED 로 시작한다(아직 지급 전).
     */
    @Test
    @DisplayName("status 미지정 시 기본값 SCHEDULED")
    void builder_defaultsStatusToScheduled() {
        assertThat(newMinimalBuilder().build().getStatus()).isEqualTo("SCHEDULED");
    }

    /**
     * 필수 식별자·금액이 빠진 행은 만들 수 없다 — 반쪽 애그리거트 차단.
     */
    @Test
    @DisplayName("필수 필드 누락 시 재구성 거부")
    void builder_rejectsMissingRequiredFields() {
        assertThatThrownBy(() -> newMinimalBuilder().commissionId(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("commissionId");

        assertThatThrownBy(() -> newMinimalBuilder().policyId(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("policyId");

        assertThatThrownBy(() -> newMinimalBuilder().fcId(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fcId");

        assertThatThrownBy(() -> newMinimalBuilder().installmentAmount(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("installmentAmount");

        assertThatThrownBy(() -> newMinimalBuilder().firstYearTotal(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("firstYearTotal");
    }

    private static CommissionSchedule.Builder newMinimalBuilder() {
        return CommissionSchedule.builder()
                .commissionId("commission-uuid-1")
                .policyId("policy-uuid-1")
                .fcId("fc-100")
                .installmentNo(1)
                .installmentAmount(new BigDecimal("10000.00"))
                .firstYearTotal(new BigDecimal("120000.00"));
    }
}
