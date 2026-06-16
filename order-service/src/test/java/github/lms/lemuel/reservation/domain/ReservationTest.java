package github.lms.lemuel.reservation.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationTest {

    private static Reservation newReservation() {
        return Reservation.register(
                10L,
                LocalDate.of(2026, 7, 1),
                "서울시 강남구 1번지",
                "홍길동",
                "010-1234-5678",
                new BigDecimal("32.50"));
    }

    @Nested
    @DisplayName("등록(register) 검증")
    class Register {

        @Test
        @DisplayName("필수 항목이 채워지면 REQUESTED 상태로 생성된다")
        void register_success() {
            Reservation r = newReservation();

            assertThat(r.getStatus()).isEqualTo(ReservationStatus.REQUESTED);
            assertThat(r.getCompanyId()).isEqualTo(10L);
            assertThat(r.getConstructionArea()).isEqualByComparingTo("32.50");
            assertThat(r.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("companyId 가 없으면 예외")
        void register_nullCompany() {
            assertThatThrownBy(() -> Reservation.register(
                    null, LocalDate.of(2026, 7, 1), "주소", "담당자", "010-0000-0000", BigDecimal.ONE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("companyId");
        }

        @Test
        @DisplayName("시공 면적이 0 이하이면 예외")
        void register_nonPositiveArea() {
            assertThatThrownBy(() -> Reservation.register(
                    10L, LocalDate.of(2026, 7, 1), "주소", "담당자", "010-0000-0000", BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("constructionArea");
        }

        @Test
        @DisplayName("확장인데 확장면적이 없으면 검증 예외")
        void validate_expansionWithoutArea() {
            Reservation r = newReservation();
            r.setExpansion(true);
            r.setExpansionArea(BigDecimal.ZERO);

            assertThatThrownBy(r::validate)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expansionArea");
        }

        @Test
        @DisplayName("보양작업인데 보양평수가 없으면 검증 예외")
        void validate_protectionWithoutArea() {
            Reservation r = newReservation();
            r.setProtectionWork(true);
            r.setProtectionArea(BigDecimal.ZERO);

            assertThatThrownBy(r::validate)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("protectionArea");
        }
    }

    @Nested
    @DisplayName("상태 전이")
    class Transitions {

        @Test
        @DisplayName("정상 경로: REQUESTED → CONFIRMED → ASSIGNED → IN_PROGRESS → COMPLETED")
        void happyPath() {
            Reservation r = newReservation();

            r.confirm();
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            r.assign(20L);
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.ASSIGNED);
            r.start();
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.IN_PROGRESS);
            r.complete();
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        }

        @Test
        @DisplayName("순서를 건너뛰면 IllegalStateException")
        void skipTransition() {
            Reservation r = newReservation(); // REQUESTED

            assertThatThrownBy(() -> r.assign(20L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("expected CONFIRMED");
            assertThatThrownBy(r::start).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(r::complete).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("배정 시 technicianId 가 저장된다")
        void assign_setsTechnician() {
            Reservation r = newReservation();
            r.confirm();

            r.assign(20L);

            assertThat(r.getStatus()).isEqualTo(ReservationStatus.ASSIGNED);
            assertThat(r.getTechnicianId()).isEqualTo(20L);
        }

        @Test
        @DisplayName("technicianId 없이 배정하면 예외")
        void assign_nullTechnicianFails() {
            Reservation r = newReservation();
            r.confirm();

            assertThatThrownBy(() -> r.assign(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("technicianId");
        }

        @Test
        @DisplayName("재배정: ASSIGNED 상태에서 다른 기사로 교체 (상태 유지)")
        void reassign_success() {
            Reservation r = newReservation();
            r.confirm();
            r.assign(20L);

            r.reassign(30L);

            assertThat(r.getStatus()).isEqualTo(ReservationStatus.ASSIGNED);
            assertThat(r.getTechnicianId()).isEqualTo(30L);
        }

        @Test
        @DisplayName("재배정: 배정 전(CONFIRMED)에는 불가")
        void reassign_beforeAssignFails() {
            Reservation r = newReservation();
            r.confirm();

            assertThatThrownBy(() -> r.reassign(30L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("expected ASSIGNED");
        }

        @Test
        @DisplayName("재배정: 시공 시작 후(IN_PROGRESS)에는 불가")
        void reassign_afterStartFails() {
            Reservation r = newReservation();
            r.confirm();
            r.assign(20L);
            r.start();

            assertThatThrownBy(() -> r.reassign(30L))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("재배정: 동일 기사로는 불가")
        void reassign_sameTechnicianFails() {
            Reservation r = newReservation();
            r.confirm();
            r.assign(20L);

            assertThatThrownBy(() -> r.reassign(20L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Already assigned");
        }

        @Test
        @DisplayName("비종료 상태에서 취소 가능, 사유가 기록된다")
        void cancel_fromNonTerminal() {
            Reservation r = newReservation();
            r.confirm();

            r.cancel("고객 변심");

            assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELED);
            assertThat(r.getCanceledReason()).isEqualTo("고객 변심");
        }

        @Test
        @DisplayName("완료된 예약은 취소할 수 없다")
        void cancel_completedReservationFails() {
            Reservation r = newReservation();
            r.confirm();
            r.assign(20L);
            r.start();
            r.complete();

            assertThatThrownBy(() -> r.cancel("늦은 취소"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("terminal");
        }

        @Test
        @DisplayName("이미 취소된 예약은 다시 취소할 수 없다")
        void cancel_alreadyCanceledFails() {
            Reservation r = newReservation();
            r.cancel("최초 취소");

            assertThatThrownBy(() -> r.cancel("중복 취소"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("요금 반영")
    class Fees {

        @Test
        @DisplayName("계산된 보양비/추가비용이 반영된다")
        void applyCalculatedFees() {
            Reservation r = newReservation();

            r.applyCalculatedFees(new BigDecimal("50000"), new BigDecimal("12000"));

            assertThat(r.getProtectionFee()).isEqualByComparingTo("50000");
            assertThat(r.getAdditionalFee()).isEqualByComparingTo("12000");
        }

        @Test
        @DisplayName("null 요금은 0 으로 보정된다")
        void applyCalculatedFees_nullDefaultsToZero() {
            Reservation r = newReservation();

            r.applyCalculatedFees(null, null);

            assertThat(r.getProtectionFee()).isEqualByComparingTo("0");
            assertThat(r.getAdditionalFee()).isEqualByComparingTo("0");
        }
    }
}
