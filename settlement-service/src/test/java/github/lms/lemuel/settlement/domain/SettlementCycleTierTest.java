package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SellerTier ↔ SettlementCycle 매핑 + T+N 영업일 정산일 계산 검증.
 */
class SettlementCycleTierTest {

    @Test
    @DisplayName("SellerTier 별 default cycle — STRATEGIC/VIP/NORMAL = T+1/T+3/T+7")
    void tierToDefaultCycle() {
        assertThat(SellerTier.STRATEGIC.defaultCycle()).isEqualTo(SettlementCycle.T_PLUS_1);
        assertThat(SellerTier.VIP.defaultCycle()).isEqualTo(SettlementCycle.T_PLUS_3);
        assertThat(SellerTier.NORMAL.defaultCycle()).isEqualTo(SettlementCycle.T_PLUS_7);
    }

    @Test
    @DisplayName("T+1: 화요일 결제 → 수요일 정산")
    void tPlus1_weekday() {
        LocalDate paid = LocalDate.of(2026, 4, 28); // 화
        assertThat(SettlementCycle.T_PLUS_1.resolveSettlementDate(paid))
                .isEqualTo(LocalDate.of(2026, 4, 29)); // 수
    }

    @Test
    @DisplayName("T+1: 금요일 결제 → 다음주 월요일 정산 (주말 건너뜀)")
    void tPlus1_friday() {
        LocalDate paid = LocalDate.of(2026, 5, 1); // 금
        assertThat(SettlementCycle.T_PLUS_1.resolveSettlementDate(paid))
                .isEqualTo(LocalDate.of(2026, 5, 4)); // 월
    }

    @Test
    @DisplayName("T+3: 월요일 결제 → 같은 주 목요일 (3 영업일 후)")
    void tPlus3_monday() {
        LocalDate paid = LocalDate.of(2026, 4, 27); // 월
        assertThat(SettlementCycle.T_PLUS_3.resolveSettlementDate(paid))
                .isEqualTo(LocalDate.of(2026, 4, 30)); // 목
    }

    @Test
    @DisplayName("T+7: 월요일 결제 → 7 영업일 후 (주말 + 어린이날 스킵)")
    void tPlus7_monday() {
        LocalDate paid = LocalDate.of(2026, 4, 27); // 월
        // +1=4/28 화, +2=4/29 수, +3=4/30 목, +4=5/1 금,
        // 주말 5/2-5/3 스킵, +5=5/4 월, 5/5 화 어린이날 스킵, +6=5/6 수, +7=5/7 목
        assertThat(SettlementCycle.T_PLUS_7.resolveSettlementDate(paid))
                .isEqualTo(LocalDate.of(2026, 5, 7));
    }

    @Test
    @DisplayName("DAILY 기존 동작 보존: 단순 +1일")
    void daily_legacy() {
        LocalDate paid = LocalDate.of(2026, 5, 1); // 금
        // DAILY 는 영업일 무관 +1 (레거시 호환)
        assertThat(SettlementCycle.DAILY.resolveSettlementDate(paid))
                .isEqualTo(LocalDate.of(2026, 5, 2)); // 토 — 레거시 동작 그대로
    }

    @Test
    @DisplayName("fromStringOrDefault: T_PLUS_1 같은 새 값도 인식")
    void fromString() {
        assertThat(SettlementCycle.fromStringOrDefault("T_PLUS_1")).isEqualTo(SettlementCycle.T_PLUS_1);
        assertThat(SettlementCycle.fromStringOrDefault("t_plus_3")).isEqualTo(SettlementCycle.T_PLUS_3);
        assertThat(SettlementCycle.fromStringOrDefault("UNKNOWN")).isEqualTo(SettlementCycle.DAILY);
    }
}
