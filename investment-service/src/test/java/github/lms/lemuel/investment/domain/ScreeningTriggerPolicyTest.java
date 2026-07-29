package github.lms.lemuel.investment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ScreeningTriggerPolicy — 시세 기준일 앵커 + 미갱신 스킵 판정 검증.
 *
 * <p>시세는 T+1 로 적재되므로 "오늘이 휴장일인가"는 판정할 수 없다. 대신 <b>새 종가가 도착했는가</b>로
 * 판정하면 휴장일 다음 실행에서 자연히 스킵된다(휴장일엔 새 종가가 생기지 않으므로).
 */
class ScreeningTriggerPolicyTest {

    private static final LocalDate FRI = LocalDate.of(2026, 7, 24);
    private static final LocalDate MON = LocalDate.of(2026, 7, 27);
    private static final LocalDate TUE = LocalDate.of(2026, 7, 28);

    private final ScreeningTriggerPolicy policy = new ScreeningTriggerPolicy();

    private static DailyClose close(LocalDate date) {
        return new DailyClose(date, new BigDecimal("50000"));
    }

    @Test
    @DisplayName("새 종가가 도착하면 그 종가일로 스크리닝한다 (추천일 = 시세 기준일)")
    void 새_종가가_도착하면_그_종가일로_스크리닝() {
        ScreeningTrigger trigger = policy.decide(List.of(close(MON), close(TUE)), MON);

        assertThat(trigger.decision()).isEqualTo(ScreeningTrigger.Decision.SCREEN);
        assertThat(trigger.quoteBaseDate()).isEqualTo(TUE);
        assertThat(trigger.shouldScreen()).isTrue();
    }

    @Test
    @DisplayName("저장된 세트가 없으면(최초 실행) 최신 종가일로 스크리닝한다")
    void 저장된_세트가_없으면_스크리닝() {
        ScreeningTrigger trigger = policy.decide(List.of(close(TUE)), null);

        assertThat(trigger.decision()).isEqualTo(ScreeningTrigger.Decision.SCREEN);
        assertThat(trigger.quoteBaseDate()).isEqualTo(TUE);
    }

    @Test
    @DisplayName("최신 종가일 세트가 이미 있으면 스킵한다 — 휴장일 다음 실행이 여기 해당")
    void 최신_종가일_세트가_이미_있으면_스킵() {
        ScreeningTrigger trigger = policy.decide(List.of(close(MON), close(TUE)), TUE);

        assertThat(trigger.decision()).isEqualTo(ScreeningTrigger.Decision.SKIP_UP_TO_DATE);
        assertThat(trigger.quoteBaseDate()).isEqualTo(TUE);
        assertThat(trigger.shouldScreen()).isFalse();
    }

    @Test
    @DisplayName("종가를 한 건도 못 구하면 스킵한다 — 근거 없이 세트를 갈아엎지 않는다")
    void 종가가_없으면_스킵() {
        ScreeningTrigger trigger = policy.decide(List.of(), TUE);

        assertThat(trigger.decision()).isEqualTo(ScreeningTrigger.Decision.SKIP_NO_QUOTES);
        assertThat(trigger.quoteBaseDate()).isNull();
        assertThat(trigger.shouldScreen()).isFalse();
    }

    @Test
    @DisplayName("종가 목록이 null 이어도 스킵으로 안전하게 떨어진다")
    void 종가가_null_이면_스킵() {
        assertThat(policy.decide(null, TUE).decision())
                .isEqualTo(ScreeningTrigger.Decision.SKIP_NO_QUOTES);
    }

    @Test
    @DisplayName("종목별 종가 최신일이 다르면 가장 최신을 시세 기준일로 삼는다")
    void 여러_종목_중_가장_최신_종가일을_채택() {
        // 한 종목만 거래 정지·데이터 지연이어도 나머지가 최신이면 그날로 판정한다.
        ScreeningTrigger trigger = policy.decide(List.of(close(FRI), close(TUE), close(MON)), FRI);

        assertThat(trigger.quoteBaseDate()).isEqualTo(TUE);
        assertThat(trigger.shouldScreen()).isTrue();
    }

    @Test
    @DisplayName("저장 세트 날짜가 시세보다 미래여도(레거시 실행일 기준 세트) 최신 종가일로 스크리닝한다")
    void 저장_세트가_미래_날짜여도_스크리닝() {
        // 앵커 변경 이전에 '실행일' 기준으로 저장된 세트가 남아 있는 배포 직후 상황.
        ScreeningTrigger trigger = policy.decide(List.of(close(MON)), TUE);

        assertThat(trigger.decision()).isEqualTo(ScreeningTrigger.Decision.SCREEN);
        assertThat(trigger.quoteBaseDate()).isEqualTo(MON);
    }
}
