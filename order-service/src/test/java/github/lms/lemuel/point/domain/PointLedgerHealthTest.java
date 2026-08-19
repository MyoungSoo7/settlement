package github.lms.lemuel.point.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포인트 원장 3자 대조 — 콘솔이 "잔액이 맞는가"에 답할 수 있어야 한다.
 *
 * <p>계정 요약(available) · 로트 상세(ACTIVE remaining 합) · 원장 누계(GRANT+RESTORE−USE−EXPIRE−REVOKE)
 * 는 서로 다른 경로로 갱신되므로, 셋이 어긋나면 어딘가에서 잔고만 움직이고 기록이 빠진 것이다.
 */
class PointLedgerHealthTest {

    private static BigDecimal won(String v) {
        return new BigDecimal(v);
    }

    @Nested
    @DisplayName("균형")
    class Balanced {

        @Test
        @DisplayName("세 축이 같으면 균형이다")
        void allThreeEqual() {
            PointLedgerHealth health = PointLedgerHealth.of(won("1000"), won("1000"), won("1000"));

            assertThat(health.balanced()).isTrue();
            assertThat(health.lotDrift()).isEqualByComparingTo("0");
            assertThat(health.entryDrift()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("스케일만 다른 값은 같은 값으로 본다 — JDBC 가 NUMERIC(19,2) 를 1000.00 으로 준다")
        void scaleDoesNotBreakBalance() {
            PointLedgerHealth health = PointLedgerHealth.of(won("1000.00"), won("1000"), won("1000.0"));

            assertThat(health.balanced()).isTrue();
        }

        @Test
        @DisplayName("모두 0 이면 균형이다 — 거래가 없는 신규 계정")
        void emptyAccountIsBalanced() {
            PointLedgerHealth health = PointLedgerHealth.of(won("0"), won("0"), won("0"));

            assertThat(health.balanced()).isTrue();
        }
    }

    @Nested
    @DisplayName("드리프트")
    class Drift {

        @Test
        @DisplayName("로트 합계가 모자라면 로트 드리프트가 양수로 잡힌다")
        void lotShortfall() {
            PointLedgerHealth health = PointLedgerHealth.of(won("1000"), won("700"), won("1000"));

            assertThat(health.balanced()).isFalse();
            assertThat(health.lotDrift()).isEqualByComparingTo("300");
            assertThat(health.entryDrift()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("로트 합계가 남으면 로트 드리프트가 음수로 잡힌다")
        void lotExcess() {
            PointLedgerHealth health = PointLedgerHealth.of(won("1000"), won("1200"), won("1000"));

            assertThat(health.lotDrift()).isEqualByComparingTo("-200");
            assertThat(health.balanced()).isFalse();
        }

        @Test
        @DisplayName("원장 누계만 어긋나도 균형이 깨진다 — 잔고만 움직이고 엔트리를 빠뜨린 경우")
        void entryOnlyDrift() {
            PointLedgerHealth health = PointLedgerHealth.of(won("1000"), won("1000"), won("900"));

            assertThat(health.lotDrift()).isEqualByComparingTo("0");
            assertThat(health.entryDrift()).isEqualByComparingTo("100");
            assertThat(health.balanced()).isFalse();
        }
    }

    @Nested
    @DisplayName("입력 정규화")
    class Normalization {

        @Test
        @DisplayName("null 은 0 으로 읽는다 — 로트도 엔트리도 없는 계정에서 SUM 이 NULL 로 온다")
        void nullBecomesZero() {
            PointLedgerHealth health = PointLedgerHealth.of(won("0"), null, null);

            assertThat(health.activeLotRemaining()).isEqualByComparingTo("0");
            assertThat(health.entryNet()).isEqualByComparingTo("0");
            assertThat(health.balanced()).isTrue();
        }

        @Test
        @DisplayName("계정 잔액이 null 이어도 터지지 않는다 — 조회 실패를 0 으로 착지시킨다")
        void nullAvailable() {
            PointLedgerHealth health = PointLedgerHealth.of(null, won("500"), won("500"));

            assertThat(health.accountAvailable()).isEqualByComparingTo("0");
            assertThat(health.lotDrift()).isEqualByComparingTo("-500");
        }
    }
}
