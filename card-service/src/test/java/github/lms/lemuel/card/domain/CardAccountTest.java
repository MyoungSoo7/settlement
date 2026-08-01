package github.lms.lemuel.card.domain;

import github.lms.lemuel.card.domain.exception.InvalidCardTransitionException;
import github.lms.lemuel.card.domain.exception.SubLimitExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardAccountTest {

    private static CardAccount screening() {
        return CardAccount.open(3001L, "777");
    }

    private static LimitSnapshot snapshot() {
        return new LimitSnapshot(
                new BigDecimal("200000.00"), new BigDecimal("50000.00"),
                new BigDecimal("0.70"), ReputationGrade.B, "floor(F x R x H)");
    }

    @Test
    @DisplayName("개설 직후는 SCREENING 이고 마스터 한도는 0")
    void opensInScreening() {
        CardAccount account = screening();
        assertThat(account.getStatus()).isEqualTo(CardAccountStatus.SCREENING);
        assertThat(account.getMasterLimit()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("심사 통과 → ACTIVE + 한도·근거 스냅샷 보존")
    void activateSetsLimitAndSnapshot() {
        CardAccount account = screening();

        account.activate(new BigDecimal("175000"), snapshot());

        assertThat(account.getStatus()).isEqualTo(CardAccountStatus.ACTIVE);
        assertThat(account.getMasterLimit()).isEqualByComparingTo("175000");
        assertThat(account.getLimitSnapshot().reputationGrade()).isEqualTo(ReputationGrade.B);
    }

    @Test
    @DisplayName("REJECTED 는 터미널 — 어떤 전이도 불가")
    void rejectedIsTerminal() {
        CardAccount account = screening();
        account.reject();

        assertThat(account.getStatus()).isEqualTo(CardAccountStatus.REJECTED);
        assertThatThrownBy(() -> account.activate(new BigDecimal("100000"), snapshot()))
                .isInstanceOf(InvalidCardTransitionException.class);
        assertThatThrownBy(account::suspend).isInstanceOf(InvalidCardTransitionException.class);
    }

    @Test
    @DisplayName("CLOSED 는 터미널 — 재활성 불가")
    void closedIsTerminal() {
        CardAccount account = screening();
        account.activate(new BigDecimal("100000"), snapshot());
        account.close();

        assertThatThrownBy(account::suspend).isInstanceOf(InvalidCardTransitionException.class);
        assertThatThrownBy(() -> account.changeMasterLimit(new BigDecimal("200000"), BigDecimal.ZERO))
                .isInstanceOf(InvalidCardTransitionException.class);
    }

    @Test
    @DisplayName("ACTIVE ⇄ SUSPENDED 는 왕복 가능")
    void activeSuspendedRoundTrip() {
        CardAccount account = screening();
        account.activate(new BigDecimal("100000"), snapshot());

        account.suspend();
        assertThat(account.getStatus()).isEqualTo(CardAccountStatus.SUSPENDED);
        account.resume();
        assertThat(account.getStatus()).isEqualTo(CardAccountStatus.ACTIVE);
    }

    // ── 불변식: masterLimit >= Σ subLimit ──

    @Test
    @DisplayName("서브한도 합계가 마스터 한도를 넘으면 발급 거부")
    void issueRejectedWhenSumExceedsMaster() {
        CardAccount account = screening();
        account.activate(new BigDecimal("100000"), snapshot());

        assertThatThrownBy(() ->
                account.assertCanIssue(new BigDecimal("90000"), new BigDecimal("20000")))
                .isInstanceOf(SubLimitExceededException.class);
    }

    @Test
    @DisplayName("합계가 마스터 한도와 정확히 같으면 허용 — 경계값")
    void issueAllowedAtExactBoundary() {
        CardAccount account = screening();
        account.activate(new BigDecimal("100000"), snapshot());

        account.assertCanIssue(new BigDecimal("90000"), new BigDecimal("10000"));  // 예외 없음
    }

    @Test
    @DisplayName("ACTIVE 가 아니면 발급 불가")
    void issueRejectedWhenNotActive() {
        CardAccount account = screening();
        account.activate(new BigDecimal("100000"), snapshot());
        account.suspend();

        assertThatThrownBy(() -> account.assertCanIssue(BigDecimal.ZERO, new BigDecimal("1000")))
                .isInstanceOf(InvalidCardTransitionException.class);
    }

    // ── 한도 하향 클램프 ──

    @Test
    @DisplayName("상향은 그대로 반영")
    void raiseAppliesDirectly() {
        CardAccount account = screening();
        account.activate(new BigDecimal("100000"), snapshot());

        LimitChangeResult result = account.changeMasterLimit(new BigDecimal("150000"), new BigDecimal("80000"));

        assertThat(result.appliedLimit()).isEqualByComparingTo("150000");
        assertThat(result.clamped()).isFalse();
        assertThat(account.getMasterLimit()).isEqualByComparingTo("150000");
    }

    @Test
    @DisplayName("하향이 서브한도 합계보다 낮으면 합계까지만 내린다 — 발급된 카드를 조용히 죽이지 않는다")
    void lowerClampsToSubLimitSum() {
        CardAccount account = screening();
        account.activate(new BigDecimal("100000"), snapshot());

        LimitChangeResult result = account.changeMasterLimit(new BigDecimal("50000"), new BigDecimal("80000"));

        assertThat(result.appliedLimit()).isEqualByComparingTo("80000");
        assertThat(result.clamped()).isTrue();
        assertThat(account.getMasterLimit()).isEqualByComparingTo("80000");
    }

    @Test
    @DisplayName("하향이 서브한도 합계 이상이면 클램프하지 않는다 — 경계값")
    void lowerNotClampedWhenAboveSum() {
        CardAccount account = screening();
        account.activate(new BigDecimal("100000"), snapshot());

        LimitChangeResult result = account.changeMasterLimit(new BigDecimal("80000"), new BigDecimal("80000"));

        assertThat(result.appliedLimit()).isEqualByComparingTo("80000");
        assertThat(result.clamped()).isFalse();
    }

    @Test
    @DisplayName("음수 한도는 거부")
    void negativeLimitRejected() {
        CardAccount account = screening();
        account.activate(new BigDecimal("100000"), snapshot());

        assertThatThrownBy(() -> account.changeMasterLimit(new BigDecimal("-1"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 그 외 계약(Produces 절 시그니처) ──

    @Test
    @DisplayName("근거(LimitSnapshot) 없이는 ACTIVE 로 전이할 수 없다")
    void activateRequiresSnapshot() {
        CardAccount account = screening();

        assertThatThrownBy(() -> account.activate(new BigDecimal("100000"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reject(reason) — 사유만 남기고 근거 스냅샷 없이 탈락")
    void rejectWithReasonOnly() {
        CardAccount account = screening();

        account.reject("재무제표 미제출");

        assertThat(account.getStatus()).isEqualTo(CardAccountStatus.REJECTED);
        assertThat(account.getRejectReason()).isEqualTo("재무제표 미제출");
        assertThat(account.getLimitSnapshot()).isNull();
    }

    @Test
    @DisplayName("reject(reason, snapshot) — 근거 없는 거절을 남기지 않는다")
    void rejectWithReasonAndSnapshot() {
        CardAccount account = screening();

        account.reject("평판등급 기준 미달", snapshot());

        assertThat(account.getStatus()).isEqualTo(CardAccountStatus.REJECTED);
        assertThat(account.getRejectReason()).isEqualTo("평판등급 기준 미달");
        assertThat(account.getLimitSnapshot().reputationGrade()).isEqualTo(ReputationGrade.B);
    }

    @Test
    @DisplayName("Builder 로 영속 상태를 재구성한다 — id/sellerId/rejectReason/version 포함")
    void builderReconstitutesFullState() {
        CardAccount account = CardAccount.builder()
                .id(42L)
                .organizationId(3001L)
                .sellerId("777")
                .status(CardAccountStatus.SUSPENDED)
                .masterLimit(new BigDecimal("100000"))
                .limitSnapshot(snapshot())
                .rejectReason(null)
                .version(3L)
                .build();

        assertThat(account.getId()).isEqualTo(42L);
        assertThat(account.getOrganizationId()).isEqualTo(3001L);
        assertThat(account.getSellerId()).isEqualTo("777");
        assertThat(account.getStatus()).isEqualTo(CardAccountStatus.SUSPENDED);
        assertThat(account.getVersion()).isEqualTo(3L);
        assertThat(account.getRejectReason()).isNull();
    }
}
