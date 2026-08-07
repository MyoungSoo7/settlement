package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidCommissionTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CommissionSchedule 상태머신 행동 테스트 — 지급·환수·취소 배치가 딛는 돈 경로.
 *
 * <p>허용 전이 4개:
 * <ol>
 *   <li>SCHEDULED → PAID : 지급 배치 (markPaid)</li>
 *   <li>SCHEDULED → CANCELLED : 계약 종료로 미지급 회차 소멸 (cancelRemaining)</li>
 *   <li>PAID → CLAWBACK_PENDING : 환수 트리거 (flagClawbackPending)</li>
 *   <li>CLAWBACK_PENDING → CLAWED_BACK : 환수금 수납 확인 (confirmClawedBack)</li>
 * </ol>
 * 그 외 모든 전이는 {@link InvalidCommissionTransitionException} — 조용한 무시 금지.
 */
@DisplayName("CommissionSchedule 지급·환수 상태머신")
class CommissionScheduleLifecycleTest {

    private static final LocalDate PAY_DATE = LocalDate.of(2026, 8, 10);

    private CommissionSchedule scheduled() {
        return CommissionSchedule.builder()
                .commissionId("commission-uuid-1")
                .policyId("policy-uuid-1")
                .fcId("fc-100")
                .recipientType(CommissionConstants.RECIPIENT_TYPE_FC)
                .installmentNo(1)
                .installmentAmount(new BigDecimal("8333.33"))
                .firstYearTotal(new BigDecimal("100000.00"))
                .dueDate(LocalDate.of(2026, 8, 1))
                .build();
    }

    @Nested
    @DisplayName("markPaid — 지급")
    class MarkPaid {

        @Test
        @DisplayName("SCHEDULED 회차를 지급하면 PAID + 지급일 + 지급액(=회차액)이 기록된다")
        void paysScheduledInstallment() {
            CommissionSchedule s = scheduled();

            s.markPaid(PAY_DATE);

            assertThat(s.getStatus()).isEqualTo(CommissionStatus.PAID);
            assertThat(s.getPaidAt()).isEqualTo(PAY_DATE);
            assertThat(s.getPaidAmount()).isEqualByComparingTo(new BigDecimal("8333.33"));
        }

        @Test
        @DisplayName("이미 PAID 인 회차 재지급은 거부한다 — 이중 지급 차단")
        void rejectsDoublePayment() {
            CommissionSchedule s = scheduled();
            s.markPaid(PAY_DATE);

            assertThatThrownBy(() -> s.markPaid(PAY_DATE.plusDays(1)))
                    .isInstanceOf(InvalidCommissionTransitionException.class);

            // 원 지급 기록은 훼손되지 않는다
            assertThat(s.getPaidAt()).isEqualTo(PAY_DATE);
            assertThat(s.getPaidAmount()).isEqualByComparingTo(new BigDecimal("8333.33"));
        }

        @Test
        @DisplayName("지급일 null 은 거부한다")
        void rejectsNullPayDate() {
            assertThatThrownBy(() -> scheduled().markPaid(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("paidAt");
        }

        @Test
        @DisplayName("CANCELLED 회차는 지급할 수 없다")
        void rejectsPayingCancelled() {
            CommissionSchedule s = scheduled();
            s.cancelRemaining();

            assertThatThrownBy(() -> s.markPaid(PAY_DATE))
                    .isInstanceOf(InvalidCommissionTransitionException.class);
        }
    }

    @Nested
    @DisplayName("cancelRemaining — 미지급 회차 소멸")
    class CancelRemaining {

        @Test
        @DisplayName("SCHEDULED 회차는 CANCELLED 로 소멸한다 (계약 종료 시)")
        void cancelsScheduledInstallment() {
            CommissionSchedule s = scheduled();

            s.cancelRemaining();

            assertThat(s.getStatus()).isEqualTo(CommissionStatus.CANCELLED);
            assertThat(s.getPaidAt()).isNull();
            assertThat(s.getPaidAmount()).isNull();
        }

        @Test
        @DisplayName("이미 지급된 회차는 소멸시킬 수 없다 — 환수 경로(flagClawbackPending)로만 회수한다")
        void rejectsCancellingPaid() {
            CommissionSchedule s = scheduled();
            s.markPaid(PAY_DATE);

            assertThatThrownBy(s::cancelRemaining)
                    .isInstanceOf(InvalidCommissionTransitionException.class);
        }
    }

    @Nested
    @DisplayName("flagClawbackPending / confirmClawedBack — 환수")
    class Clawback {

        @Test
        @DisplayName("PAID 회차는 환수대기(CLAWBACK_PENDING)로 전이한다")
        void flagsPaidForClawback() {
            CommissionSchedule s = scheduled();
            s.markPaid(PAY_DATE);

            s.flagClawbackPending();

            assertThat(s.getStatus()).isEqualTo(CommissionStatus.CLAWBACK_PENDING);
        }

        @Test
        @DisplayName("미지급(SCHEDULED) 회차는 환수 대상이 아니다")
        void rejectsFlaggingUnpaid() {
            assertThatThrownBy(() -> scheduled().flagClawbackPending())
                    .isInstanceOf(InvalidCommissionTransitionException.class);
        }

        @Test
        @DisplayName("환수대기 회차는 수납 확인 시 CLAWED_BACK 으로 종결된다")
        void confirmsClawedBack() {
            CommissionSchedule s = scheduled();
            s.markPaid(PAY_DATE);
            s.flagClawbackPending();

            s.confirmClawedBack();

            assertThat(s.getStatus()).isEqualTo(CommissionStatus.CLAWED_BACK);
        }

        @Test
        @DisplayName("환수대기 상태가 아니면 수납 확인할 수 없다")
        void rejectsConfirmingNonPending() {
            CommissionSchedule s = scheduled();
            s.markPaid(PAY_DATE);

            assertThatThrownBy(s::confirmClawedBack)
                    .isInstanceOf(InvalidCommissionTransitionException.class);
        }
    }

    @Nested
    @DisplayName("전이표 — CommissionStatus")
    class TransitionTable {

        @Test
        @DisplayName("허용 전이는 정확히 4개다")
        void allowsExactlyFourTransitions() {
            assertThat(CommissionStatus.SCHEDULED.canTransitionTo(CommissionStatus.PAID)).isTrue();
            assertThat(CommissionStatus.SCHEDULED.canTransitionTo(CommissionStatus.CANCELLED)).isTrue();
            assertThat(CommissionStatus.PAID.canTransitionTo(CommissionStatus.CLAWBACK_PENDING)).isTrue();
            assertThat(CommissionStatus.CLAWBACK_PENDING.canTransitionTo(CommissionStatus.CLAWED_BACK)).isTrue();
        }

        @Test
        @DisplayName("terminal 상태(CLAWED_BACK·CANCELLED)에서 나가는 전이는 없다")
        void terminalStatesHaveNoOutgoingTransitions() {
            for (CommissionStatus terminal : new CommissionStatus[]{
                    CommissionStatus.CLAWED_BACK, CommissionStatus.CANCELLED}) {
                assertThat(terminal.isTerminal()).isTrue();
                for (CommissionStatus target : CommissionStatus.values()) {
                    assertThat(terminal.canTransitionTo(target))
                            .as("%s → %s 는 허용되면 안 된다", terminal, target)
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("PAID → SCHEDULED 역행, SCHEDULED → CLAWBACK_PENDING 건너뛰기는 불허")
        void rejectsBackwardAndSkippingTransitions() {
            assertThat(CommissionStatus.PAID.canTransitionTo(CommissionStatus.SCHEDULED)).isFalse();
            assertThat(CommissionStatus.PAID.canTransitionTo(CommissionStatus.CANCELLED)).isFalse();
            assertThat(CommissionStatus.SCHEDULED.canTransitionTo(CommissionStatus.CLAWBACK_PENDING)).isFalse();
            assertThat(CommissionStatus.SCHEDULED.canTransitionTo(CommissionStatus.CLAWED_BACK)).isFalse();
            assertThat(CommissionStatus.CLAWBACK_PENDING.canTransitionTo(CommissionStatus.PAID)).isFalse();
        }
    }
}
