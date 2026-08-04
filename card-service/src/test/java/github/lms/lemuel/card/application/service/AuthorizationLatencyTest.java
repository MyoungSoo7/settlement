package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.out.LoadAuthorizationHoldPort;
import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.LoadCardPort;
import github.lms.lemuel.card.application.port.out.LoadMerchantPolicyPort;
import github.lms.lemuel.card.application.port.out.LoadOrgProjectionPort;
import github.lms.lemuel.card.application.port.out.PublishCardEventPort;
import github.lms.lemuel.card.application.port.out.SaveAuthorizationHoldPort;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import github.lms.lemuel.card.domain.CardStatus;
import github.lms.lemuel.card.domain.LimitSnapshot;
import github.lms.lemuel.card.domain.OrgRole;
import github.lms.lemuel.card.domain.ReputationGrade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * 승인 경로 지연 회귀 테스트 — 사후 지출관리 워크플로가 승인 p99 를 오염시키지 않음을 증명.
 *
 * <p>모든 포트를 목으로 교체해 IO 제거 후 서비스 로직만 측정한다.
 * {@code AuthorizeCardService} 가 Expense 코드에 의존하게 되면 사실상 이 테스트와
 * {@link ExpenseWorkflowDecouplingTest} 양쪽에서 동시에 실패 신호가 나타난다.
 *
 * <h3>기준</h3>
 * <ul>
 *   <li>100회 반복 → p99 ≤ 300ms</li>
 *   <li>Warm-up 10회 제외(JIT 영향 배제)</li>
 *   <li>목 기반(IO 없음) — 네트워크/DB 지연은 측정 대상 외</li>
 * </ul>
 *
 * <p><b>관측 편향 주의</b>: 이 테스트는 "Expense 코드가 승인 경로에 끼어들지 않았다"는 방어선이다.
 * 300ms 는 서비스 로직(IO 없음)에선 넉넉한 임계값 — 이 값을 초과한다면 호출 그래프가 예상보다
 * 깊다는 신호다.
 */
@ExtendWith(MockitoExtension.class)
class AuthorizationLatencyTest {

    private static final int WARMUP = 10;
    private static final int ITERATIONS = 100;
    private static final long P99_THRESHOLD_MS = 300L;

    @Mock LoadCardPort loadCardPort;
    @Mock LoadCardAccountPort loadCardAccountPort;
    @Mock LoadOrgProjectionPort loadOrgProjectionPort;
    @Mock LoadMerchantPolicyPort loadMerchantPolicyPort;
    @Mock LoadAuthorizationHoldPort loadAuthorizationHoldPort;
    @Mock SaveAuthorizationHoldPort saveAuthorizationHoldPort;
    @Mock PublishCardEventPort publishCardEventPort;

    private AuthorizeCardService service;

    @BeforeEach
    void setUp() {
        service = new AuthorizeCardService(
                loadCardPort, loadCardAccountPort, loadOrgProjectionPort,
                loadMerchantPolicyPort, loadAuthorizationHoldPort,
                saveAuthorizationHoldPort, publishCardEventPort);

        // 카드 목 — ISSUED (카드 정상 상태)
        Card card = Card.builder()
                .id(1L).cardAccountId(10L).holderUserId(100L)
                .maskedCardNo("****-****-****-1234").status(CardStatus.ISSUED)
                .subLimit(new BigDecimal("500000"))
                .build();
        when(loadCardPort.findById(anyLong())).thenReturn(Optional.of(card));

        // 카드계정 목 — ACTIVE, 비관적 락
        CardAccount account = CardAccount.builder()
                .id(10L).organizationId(1L).sellerId("S1")
                .status(CardAccountStatus.ACTIVE)
                .masterLimit(new BigDecimal("1000000"))
                .limitSnapshot(new LimitSnapshot(
                        new BigDecimal("1000000"), BigDecimal.ZERO,
                        new BigDecimal("0.70"), ReputationGrade.B, "test*0.7"))
                .build();
        when(loadCardAccountPort.findByIdForUpdate(anyLong())).thenReturn(Optional.of(account));

        // 조직 멤버 목 — 활성 STAFF
        when(loadOrgProjectionPort.findMemberRole(anyLong(), anyLong())).thenReturn(Optional.of(OrgRole.STAFF));

        // 가맹점 정책 목 — 없음(모두 허용)
        when(loadMerchantPolicyPort.findEffectivePolicy(anyLong(), anyLong())).thenReturn(Optional.empty());

        // ACTIVE 홀드 합계 목 — 0
        // 주의: sumHoldsByCardAndDate/Month 는 가맹점 정책이 있을 때만 호출되므로 여기서 스텁 불필요
        when(loadAuthorizationHoldPort.sumActiveHoldsByAccount(anyLong())).thenReturn(BigDecimal.ZERO);
        when(loadAuthorizationHoldPort.sumActiveHoldsByCard(anyLong())).thenReturn(BigDecimal.ZERO);
        when(loadAuthorizationHoldPort.findByAuthorizationId(anyString())).thenReturn(Optional.empty());

        // 저장 목
        when(saveAuthorizationHoldPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(publishCardEventPort).publishAuthorized(any(), any(), any());
    }

    @Test
    @DisplayName("승인 유스케이스 p99 ≤ 300ms (목 기반, IO 없음) — Expense 워크플로 오염 없음")
    void authorizationServiceP99WithinThreshold() {
        long[] latencies = new long[WARMUP + ITERATIONS];

        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
            String authId = "AUTH-" + UUID.randomUUID();
            long start = System.nanoTime();
            service.authorize(new github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase
                    .AuthorizeCardCommand(
                    authId, 1L, new BigDecimal("10000"), "슈퍼마트", "5411", false, false));
            latencies[i] = (System.nanoTime() - start) / 1_000_000; // ns → ms
        }

        // Warm-up 제외 후 p99 계산
        long[] measured = Arrays.copyOfRange(latencies, WARMUP, WARMUP + ITERATIONS);
        Arrays.sort(measured);
        long p99 = measured[(int) (measured.length * 0.99)];

        assertThat(p99)
                .as("승인 서비스 p99 ≤ %d ms (목 기반, IO 없음). 실제: %d ms. "
                        + "이 값을 초과하면 Expense 코드가 승인 경로에 주입됐거나 서비스 로직이 예상보다 무겁다.",
                        P99_THRESHOLD_MS, p99)
                .isLessThanOrEqualTo(P99_THRESHOLD_MS);
    }
}
