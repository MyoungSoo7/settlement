package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.out.FundingUnavailableException;
import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.LoadCardPort;
import github.lms.lemuel.card.application.port.out.LoadReputationPort;
import github.lms.lemuel.card.application.port.out.LoadSellerFundingPort;
import github.lms.lemuel.card.application.port.out.LoadSellerFundingPort.SellerFunding;
import github.lms.lemuel.card.application.port.out.PublishCardEventPort;
import github.lms.lemuel.card.application.port.out.SaveCardAccountPort;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import github.lms.lemuel.card.domain.CardLimitPolicy;
import github.lms.lemuel.card.domain.LimitChangeResult;
import github.lms.lemuel.card.domain.LimitSnapshot;
import github.lms.lemuel.card.domain.ReputationGrade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 일 1회 한도 재산정 배치 테스트.
 *
 * <p>{@link CardAccountRescreener} 를 목이 아니라 <b>실물</b>로 조립한다 — 이 배치의 값어치는
 * "몇 건 도는가"가 아니라 "한도가 실제로 얼마가 되는가"에 있고, 재심사를 목으로 대체하면
 * 클램프·강등 규칙이 전부 검증에서 빠진다. 정책도 같은 이유로 실물이다.
 *
 * <p>테스트가 {@code findByIdForUpdate} 를 {@code findAllActive} 와 <b>같은 인스턴스</b>로
 * 스텁하는 것은 편의가 아니라 운영 구조의 반영이다: 배치는 목록을 먼저 읽고, 계정마다 락을 다시
 * 잡은 뒤 그 시점의 상태로 판단한다.
 */
@ExtendWith(MockitoExtension.class)
class RecalculateCardLimitsServiceTest {

    @Mock LoadCardAccountPort loadCardAccountPort;
    @Mock SaveCardAccountPort saveCardAccountPort;
    @Mock LoadCardPort loadCardPort;
    @Mock LoadSellerFundingPort loadSellerFundingPort;
    @Mock LoadReputationPort loadReputationPort;
    @Mock PublishCardEventPort publishCardEventPort;

    RecalculateCardLimitsService service;

    @BeforeEach
    void setUp() {
        CardAccountRescreener rescreener = new CardAccountRescreener(
                loadCardAccountPort,
                saveCardAccountPort,
                loadCardPort,
                loadSellerFundingPort,
                loadReputationPort,
                publishCardEventPort,
                new CardLimitPolicy(new BigDecimal("0.70"), new BigDecimal("300000")));
        service = new RecalculateCardLimitsService(loadCardAccountPort, rescreener);
    }

    private CardAccount activeAccount(Long id, String sellerId, String masterLimit) {
        CardAccount account = CardAccount.builder()
                .id(id)
                .organizationId(3000L + id)
                .sellerId(sellerId)
                .status(CardAccountStatus.SCREENING)
                .masterLimit(BigDecimal.ZERO)
                .build();
        account.activate(new BigDecimal(masterLimit), new LimitSnapshot(
                new BigDecimal("1000000"), BigDecimal.ZERO,
                new BigDecimal("0.70"), ReputationGrade.A, "직전 심사"));
        return account;
    }

    /** 배치가 보는 목록과 락을 잡고 다시 읽는 계정을 같은 인스턴스로 연결한다. */
    private void stubBatchSees(CardAccount... accounts) {
        when(loadCardAccountPort.findAllActive()).thenReturn(List.of(accounts));
        for (CardAccount account : accounts) {
            when(loadCardAccountPort.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
        }
        when(saveCardAccountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("재원이 늘면 한도가 오른다")
    void raisesWhenFundingGrows() {
        CardAccount account = activeAccount(1L, "777", "700000");
        stubBatchSees(account);
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(new BigDecimal("500000"));
        when(loadSellerFundingPort.load("777"))
                .thenReturn(new SellerFunding(new BigDecimal("2000000"), BigDecimal.ZERO));
        when(loadReputationPort.gradeOf("777")).thenReturn(ReputationGrade.A);

        int changed = service.recalculateAll();

        assertThat(changed).isEqualTo(1);
        assertThat(account.getMasterLimit()).isEqualByComparingTo("1400000");
        verify(publishCardEventPort).publishMasterLimitChanged(eq(account), any(), any());
    }

    /**
     * 상향은 Σ서브한도와 무관하지만 하향은 아니다 — 이미 발급된 카드의 합만큼은 내려갈 수 없다.
     * 그 사실이 {@code clamped=true} 로 이벤트에 실려야 운영자가 "왜 요청한 만큼 안 내려갔나"를 안다.
     */
    @Test
    @DisplayName("하향이 Σ서브한도 아래면 클램프하고 clamped=true 로 발행한다")
    void lowerIsClampedAndFlagged() {
        CardAccount account = activeAccount(1L, "777", "1000000");
        stubBatchSees(account);
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(new BigDecimal("800000"));
        // 재원 급감: 700,000 x 0.70 x 1.00 = 490,000 → Σ서브한도 800,000 에 걸려 클램프
        when(loadSellerFundingPort.load("777"))
                .thenReturn(new SellerFunding(new BigDecimal("700000"), BigDecimal.ZERO));
        when(loadReputationPort.gradeOf("777")).thenReturn(ReputationGrade.A);

        int changed = service.recalculateAll();

        assertThat(changed).isEqualTo(1);
        assertThat(account.getMasterLimit()).isEqualByComparingTo("800000");

        ArgumentCaptor<LimitChangeResult> captor = ArgumentCaptor.forClass(LimitChangeResult.class);
        verify(publishCardEventPort).publishMasterLimitChanged(any(), any(), captor.capture());
        assertThat(captor.getValue().clamped()).isTrue();
        assertThat(captor.getValue().appliedLimit()).isEqualByComparingTo("800000");
        // 한도가 남아 있으므로 계정은 계속 ACTIVE 다 — 하향은 정지가 아니다.
        assertThat(account.getStatus()).isEqualTo(CardAccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("한 계정의 재원 조회가 실패해도 나머지는 계속 처리한다")
    void oneFailureDoesNotAbortTheBatch() {
        CardAccount failing = activeAccount(1L, "777", "700000");
        CardAccount healthy = activeAccount(2L, "778", "700000");
        stubBatchSees(failing, healthy);
        when(loadCardPort.sumActiveSubLimits(any())).thenReturn(BigDecimal.ZERO);
        when(loadSellerFundingPort.load("777")).thenThrow(new FundingUnavailableException("down", null));
        when(loadSellerFundingPort.load("778"))
                .thenReturn(new SellerFunding(new BigDecimal("2000000"), BigDecimal.ZERO));
        when(loadReputationPort.gradeOf("778")).thenReturn(ReputationGrade.A);

        int changed = service.recalculateAll();

        assertThat(changed).isEqualTo(1);
        assertThat(healthy.getMasterLimit()).isEqualByComparingTo("1400000");
        // 실패한 계정은 옛 한도 그대로 남는다 — 추정 한도를 얹지 않는다.
        assertThat(failing.getMasterLimit()).isEqualByComparingTo("700000");
    }

    @Test
    @DisplayName("한도가 그대로면 이벤트를 발행하지 않는다 — 조용한 날은 조용해야 한다")
    void noEventWhenUnchanged() {
        CardAccount account = activeAccount(1L, "777", "700000");
        when(loadCardAccountPort.findAllActive()).thenReturn(List.of(account));
        when(loadCardAccountPort.findByIdForUpdate(1L)).thenReturn(Optional.of(account));
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(BigDecimal.ZERO);
        // 같은 재원·등급 → 같은 한도 700,000
        when(loadSellerFundingPort.load("777"))
                .thenReturn(new SellerFunding(new BigDecimal("1000000"), BigDecimal.ZERO));
        when(loadReputationPort.gradeOf("777")).thenReturn(ReputationGrade.A);

        int changed = service.recalculateAll();

        assertThat(changed).isZero();
        verify(publishCardEventPort, never()).publishMasterLimitChanged(any(), any(), any());
        // 저장도 생략한다 — 매일 전 계정의 version 을 튕기면 낙관적 락 충돌만 늘어난다.
        verify(saveCardAccountPort, never()).save(any());
    }

    @Test
    @DisplayName("ACTIVE 계정만 재산정한다 — findAllActive 외의 계정은 조회조차 하지 않는다")
    void onlyActiveAccountsAreRecalculated() {
        when(loadCardAccountPort.findAllActive()).thenReturn(List.of());

        int changed = service.recalculateAll();

        assertThat(changed).isZero();
        verify(loadSellerFundingPort, never()).load(anyString());
    }

    /**
     * 목록 조회와 락 획득 사이는 트랜잭션 밖이라 그 사이에 사람이 계정을 정지시킬 수 있다.
     * 그때 배치가 재산정을 밀어붙이면 수동 조치를 배치가 되돌리는 셈이 된다.
     */
    @Test
    @DisplayName("락을 잡은 시점에 ACTIVE 가 아니면 건드리지 않는다")
    void skipsAccountSuspendedBetweenListingAndLock() {
        CardAccount account = activeAccount(1L, "777", "700000");
        when(loadCardAccountPort.findAllActive()).thenReturn(List.of(account));
        account.suspend();   // 목록을 읽은 뒤 수동 정지
        when(loadCardAccountPort.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

        int changed = service.recalculateAll();

        assertThat(changed).isZero();
        verify(loadSellerFundingPort, never()).load(anyString());
        verify(saveCardAccountPort, never()).save(any());
    }

    @Test
    @DisplayName("재산정으로 E등급이 되면 한도 0 이 아니라 계정을 SUSPENDED 로 돌린다")
    void gradeEDowngradeSuspendsAccount() {
        // 한도만 0 으로 만들면 카드가 남아있는 채로 사실상 무력화된다 —
        // 상태를 명시적으로 바꿔서 "왜 안 되는지"가 드러나게 한다.
        CardAccount account = activeAccount(1L, "777", "700000");
        stubBatchSees(account);
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(new BigDecimal("500000"));
        when(loadSellerFundingPort.load("777"))
                .thenReturn(new SellerFunding(new BigDecimal("1000000"), BigDecimal.ZERO));
        when(loadReputationPort.gradeOf("777")).thenReturn(ReputationGrade.E);

        service.recalculateAll();

        assertThat(account.getStatus()).isEqualTo(CardAccountStatus.SUSPENDED);
        // 한도는 클램프 규칙에 따라 Σ서브한도 아래로 내려가지 않는다.
        assertThat(account.getMasterLimit()).isEqualByComparingTo("500000");
        verify(publishCardEventPort).publishAccountStatusChanged(
                eq(account), eq(CardAccountStatus.ACTIVE), anyString());
    }

    /**
     * 최소한도 미달도 탈락이다. 등급 강등만 정지 사유로 다루면 "재원이 말라 한도가 20만이 된 계정"이
     * ACTIVE 로 남아 최소한도 정책을 통과하지 못한 여신을 계속 굴린다.
     */
    @Test
    @DisplayName("산정 한도가 최소한도에 미달해도 계정을 정지한다")
    void belowMinimumAlsoSuspends() {
        CardAccount account = activeAccount(1L, "777", "700000");
        stubBatchSees(account);
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(BigDecimal.ZERO);
        // 100,000 x 0.70 = 70,000 < 최소한도 300,000
        when(loadSellerFundingPort.load("777"))
                .thenReturn(new SellerFunding(new BigDecimal("100000"), BigDecimal.ZERO));
        when(loadReputationPort.gradeOf("777")).thenReturn(ReputationGrade.A);

        int changed = service.recalculateAll();

        assertThat(changed).isEqualTo(1);
        assertThat(account.getStatus()).isEqualTo(CardAccountStatus.SUSPENDED);
        // Σ서브한도가 0 이라 클램프 하한도 0 — 발급된 카드가 없으므로 한도는 그대로 0 이 된다.
        assertThat(account.getMasterLimit()).isEqualByComparingTo("0");
        verify(publishCardEventPort).publishMasterLimitChanged(any(), any(), any());
        verify(publishCardEventPort).publishAccountStatusChanged(any(), any(), anyString());
    }
}
