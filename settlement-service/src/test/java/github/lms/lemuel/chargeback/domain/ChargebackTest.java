package github.lms.lemuel.chargeback.domain;

import org.junit.jupiter.api.Nested;
import github.lms.lemuel.chargeback.domain.exception.ChargebackInvariantViolationException;
import github.lms.lemuel.chargeback.domain.exception.InvalidChargebackStateException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Chargeback 도메인 — 상태 머신·검증 규칙·불변식.
 *
 * <p>금전 도메인이므로 다음 규칙은 모두 도메인이 거부해야 한다 (DB 도달 전):
 * <ul>
 *   <li>음수·0 금액</li>
 *   <li>PG_WEBHOOK 인데 멱등 키 누락</li>
 *   <li>OPEN 이외 상태에서 결정 시도</li>
 *   <li>decidedBy 누락</li>
 * </ul>
 */
class ChargebackTest {

    @Nested
    class Open_생성 {

        @Test
        void PG_WEBHOOK_은_pgChargebackId_필수() {
            assertThatThrownBy(() -> Chargeback.open(
                    1L, null, BigDecimal.valueOf(10_000),
                    ChargebackReason.FRAUD, "fraud notice",
                    ChargebackSource.PG_WEBHOOK, null))
                    .isInstanceOf(ChargebackInvariantViolationException.class)
                    .hasMessageContaining("pgChargebackId");
        }

        @Test
        void MANUAL_은_pgChargebackId_없어도_생성() {
            Chargeback cb = Chargeback.open(1L, null, BigDecimal.valueOf(10_000),
                    ChargebackReason.OTHER, "manual entry",
                    ChargebackSource.MANUAL, null);

            assertThat(cb.getStatus()).isEqualTo(ChargebackStatus.OPEN);
            assertThat(cb.getSource()).isEqualTo(ChargebackSource.MANUAL);
            assertThat(cb.getPgChargebackId()).isNull();
        }

        @Test
        void 음수_금액은_거부() {
            assertThatThrownBy(() -> Chargeback.open(1L, null, BigDecimal.valueOf(-1),
                    ChargebackReason.FRAUD, null,
                    ChargebackSource.PG_WEBHOOK, "PG-CB-1"))
                    .isInstanceOf(ChargebackInvariantViolationException.class)
                    .hasMessageContaining("양수");
        }

        @Test
        void 영원_금액은_거부() {
            assertThatThrownBy(() -> Chargeback.open(1L, null, BigDecimal.ZERO,
                    ChargebackReason.FRAUD, null,
                    ChargebackSource.PG_WEBHOOK, "PG-CB-2"))
                    .isInstanceOf(ChargebackInvariantViolationException.class);
        }
    }

    @Nested
    class 상태_전이 {

        private Chargeback openChargeback() {
            return Chargeback.open(1L, 100L, BigDecimal.valueOf(10_000),
                    ChargebackReason.FRAUD, "fraud",
                    ChargebackSource.PG_WEBHOOK, "PG-CB-A");
        }

        @Test
        void OPEN_에서_ACCEPT_가능() {
            Chargeback cb = openChargeback();
            cb.accept("admin@lemuel.io", "셀러 응답 없음 - 30일 경과");

            assertThat(cb.isAccepted()).isTrue();
            assertThat(cb.getDecidedBy()).isEqualTo("admin@lemuel.io");
            assertThat(cb.getDecisionNote()).contains("30일");
            assertThat(cb.getDecidedAt()).isNotNull();
        }

        @Test
        void OPEN_에서_REJECT_가능_사유필수() {
            Chargeback cb = openChargeback();
            cb.reject("admin@lemuel.io", "셀러 배송증명 제출 - 운송장 ZX123");

            assertThat(cb.isRejected()).isTrue();
            assertThat(cb.getDecisionNote()).contains("ZX123");
        }

        @Test
        void ACCEPTED_재결정_불가() {
            Chargeback cb = openChargeback();
            cb.accept("admin@lemuel.io", "셀러 응답 없음");

            assertThatThrownBy(() -> cb.reject("admin@lemuel.io", "마음이 바뀜"))
                    .isInstanceOf(InvalidChargebackStateException.class)
                    .hasMessageContaining("ACCEPTED");
        }

        @Test
        void REJECTED_재결정_불가() {
            Chargeback cb = openChargeback();
            cb.reject("admin@lemuel.io", "증빙 충분");

            assertThatThrownBy(() -> cb.accept("admin@lemuel.io", "재고려"))
                    .isInstanceOf(InvalidChargebackStateException.class);
        }

        @Test
        void ACCEPT_decidedBy_누락_거부() {
            Chargeback cb = openChargeback();

            assertThatThrownBy(() -> cb.accept(null, "any"))
                    .isInstanceOf(ChargebackInvariantViolationException.class)
                    .hasMessageContaining("decidedBy");
            assertThatThrownBy(() -> cb.accept("  ", "any"))
                    .isInstanceOf(ChargebackInvariantViolationException.class);
        }

        @Test
        void REJECT_사유_누락_거부() {
            Chargeback cb = openChargeback();

            assertThatThrownBy(() -> cb.reject("admin", null))
                    .isInstanceOf(ChargebackInvariantViolationException.class)
                    .hasMessageContaining("기각 사유");
        }
    }

    @Nested
    class linkSettlement {

        @Test
        void OPEN_상태에서_settlementId_백필_가능() {
            Chargeback cb = Chargeback.open(1L, null, BigDecimal.valueOf(5_000),
                    ChargebackReason.FRAUD, null,
                    ChargebackSource.PG_WEBHOOK, "PG-X");
            assertThat(cb.getSettlementId()).isNull();

            cb.linkSettlement(99L);

            assertThat(cb.getSettlementId()).isEqualTo(99L);
        }

        @Test
        void 종료_상태에서_settlementId_변경_불가() {
            Chargeback cb = Chargeback.open(1L, 100L, BigDecimal.valueOf(5_000),
                    ChargebackReason.FRAUD, null,
                    ChargebackSource.MANUAL, null);
            cb.accept("admin", "ok");

            assertThatThrownBy(() -> cb.linkSettlement(200L))
                    .isInstanceOf(InvalidChargebackStateException.class)
                    .hasMessageContaining("종료 상태");
        }

        @Test
        void 종료_상태라도_미연결이면_백필_가능() {
            // 정산 전 접수 → 정산 전 ACCEPT → 정산 생성 시 백필 시나리오.
            Chargeback cb = Chargeback.open(1L, null, BigDecimal.valueOf(5_000),
                    ChargebackReason.FRAUD, null,
                    ChargebackSource.MANUAL, null);
            cb.accept("admin", "ok");
            assertThat(cb.getSettlementId()).isNull();

            cb.linkSettlement(300L);

            assertThat(cb.getSettlementId()).isEqualTo(300L);
        }
    }

    @Nested
    class ChargebackStatus_상태머신 {

        @Test
        void OPEN_은_둘_다_전이_가능() {
            assertThat(ChargebackStatus.OPEN.canTransitionTo(ChargebackStatus.ACCEPTED)).isTrue();
            assertThat(ChargebackStatus.OPEN.canTransitionTo(ChargebackStatus.REJECTED)).isTrue();
        }

        @Test
        void 종료_상태는_어디로도_전이_불가() {
            for (ChargebackStatus terminal : new ChargebackStatus[]{
                    ChargebackStatus.ACCEPTED, ChargebackStatus.REJECTED}) {
                assertThat(terminal.canTransitionTo(ChargebackStatus.OPEN)).isFalse();
                assertThat(terminal.canTransitionTo(ChargebackStatus.ACCEPTED)).isFalse();
                assertThat(terminal.canTransitionTo(ChargebackStatus.REJECTED)).isFalse();
                assertThat(terminal.isFinal()).isTrue();
            }
        }
    }
}
