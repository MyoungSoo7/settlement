package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.RecalculateCardLimitsUseCase;
import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.domain.CardAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 일 1회 마스터 한도 재산정 배치.
 *
 * <p><b>이 클래스에는 트랜잭션이 없다.</b> 계정 하나가 곧 트랜잭션 하나이며
 * ({@link CardAccountRescreener}), 여기서 트랜잭션을 열면 배치 전체가 한 단위가 되어
 * 마지막 계정의 실패가 앞선 성공을 전부 롤백시킨다.
 *
 * <p>대상은 ACTIVE 계정뿐이다. 정지·해지된 계정은 재산정으로 <b>되살아나지 않는다</b> —
 * 재원이 회복돼도 배치가 자동으로 한도를 다시 얹지 않고, 복귀는 사람이 {@code resume} 을
 * 눌러야 한다. 정지 사유가 해소됐는지는 재원 숫자가 답할 수 있는 질문이 아니기 때문이다.
 * (운영 관점의 대가는 명확하다: 강등된 계정은 <b>수동 복구가 필요하다</b>.)
 */
@Service
public class RecalculateCardLimitsService implements RecalculateCardLimitsUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecalculateCardLimitsService.class);

    private final LoadCardAccountPort loadCardAccountPort;
    private final CardAccountRescreener rescreener;

    public RecalculateCardLimitsService(LoadCardAccountPort loadCardAccountPort,
                                        CardAccountRescreener rescreener) {
        this.loadCardAccountPort = loadCardAccountPort;
        this.rescreener = rescreener;
    }

    @Override
    public int recalculateAll() {
        List<CardAccount> accounts = loadCardAccountPort.findAllActive();
        int changed = 0;
        int failed = 0;
        for (CardAccount account : accounts) {
            try {
                if (rescreener.rescreen(account.getId())) {
                    changed++;
                }
            } catch (RuntimeException e) {
                // 재원 조회는 account-service 로 나가는 외부 호출이라 일부 실패가 정상 범주다.
                // 여기서 던지면 남은 계정은 오늘치 재산정을 통째로 건너뛴 채 옛 한도로 남는다 —
                // 옛 한도로 남는 것이 이 배치가 막으려는 바로 그 위험이다.
                failed++;
                log.error("[CardLimitRecalc] accountId={} sellerId={} 재산정 실패 — 건너뛴다",
                        account.getId(), account.getSellerId(), e);
            }
        }
        log.info("[CardLimitRecalc] 대상 {}건 · 변경 {}건 · 실패 {}건", accounts.size(), changed, failed);
        return changed;
    }
}
