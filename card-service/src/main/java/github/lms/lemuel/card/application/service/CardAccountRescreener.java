package github.lms.lemuel.card.application.service;

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
import github.lms.lemuel.card.domain.ReputationGrade;
import github.lms.lemuel.card.domain.ScreeningResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 카드계정 <b>한 건</b>의 재심사 — 배치의 트랜잭션 단위.
 *
 * <p>{@link RecalculateCardLimitsService} 안의 private 메서드가 아니라 별도 빈인 이유는
 * 트랜잭션 경계 때문이다. 자기 호출(self-invocation)은 Spring 프록시를 타지 않아
 * {@code @Transactional} 이 무시되고, 그러면 배치 전체가 한 트랜잭션이 되어 <b>한 계정의 실패가
 * 앞서 처리한 계정들까지 롤백</b>시킨다 — "한 건 실패해도 나머지는 계속"이 무너지는 지점이 정확히 여기다.
 *
 * <p>{@code REQUIRES_NEW} 는 호출자에 트랜잭션이 있든 없든 계정마다 독립 커밋을 보장한다.
 */
@Service
class CardAccountRescreener {

    private static final Logger log = LoggerFactory.getLogger(CardAccountRescreener.class);

    private final LoadCardAccountPort loadCardAccountPort;
    private final SaveCardAccountPort saveCardAccountPort;
    private final LoadCardPort loadCardPort;
    private final LoadSellerFundingPort loadSellerFundingPort;
    private final LoadReputationPort loadReputationPort;
    private final PublishCardEventPort publishCardEventPort;
    private final CardLimitPolicy cardLimitPolicy;

    CardAccountRescreener(LoadCardAccountPort loadCardAccountPort,
                          SaveCardAccountPort saveCardAccountPort,
                          LoadCardPort loadCardPort,
                          LoadSellerFundingPort loadSellerFundingPort,
                          LoadReputationPort loadReputationPort,
                          PublishCardEventPort publishCardEventPort,
                          CardLimitPolicy cardLimitPolicy) {
        this.loadCardAccountPort = loadCardAccountPort;
        this.saveCardAccountPort = saveCardAccountPort;
        this.loadCardPort = loadCardPort;
        this.loadSellerFundingPort = loadSellerFundingPort;
        this.loadReputationPort = loadReputationPort;
        this.publishCardEventPort = publishCardEventPort;
        this.cardLimitPolicy = cardLimitPolicy;
    }

    /**
     * 한 계정을 재심사한다.
     *
     * <p><b>락을 먼저 잡고 Σ서브한도를 읽는다</b> — 발급 유스케이스와 같은 순서다. 락 없이 하향하면
     * "합계를 읽은 뒤 마스터를 내리기 전"에 들어온 발급이 그대로 통과해 {@code masterLimit >= Σ서브}
     * 가 깨진다. 재산정은 사람이 아니라 크론이 돌리는 경로라 그 창이 매일 열린다.
     *
     * <p>재원 조회를 락 <b>안에서</b> 하는 것은 의도된 비용이다. 밖에서 먼저 조회하면 락 대기 동안
     * 재원이 낡아 "잠근 뒤에는 이미 과거인 값"으로 여신을 정하게 된다. 계정 하나의 락이라
     * 경합 범위도 그 계정으로 한정된다.
     *
     * @return 마스터 한도 또는 계정 상태가 실제로 바뀌었으면 true
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean rescreen(Long cardAccountId) {
        CardAccount account = loadCardAccountPort.findByIdForUpdate(cardAccountId).orElse(null);
        if (account == null || account.getStatus() != CardAccountStatus.ACTIVE) {
            // 목록 조회와 락 획득 사이에 상태가 바뀔 수 있다(수동 정지·해지). 그때는 건드리지 않는다.
            return false;
        }

        BigDecimal currentSubLimitSum = loadCardPort.sumActiveSubLimits(cardAccountId);
        SellerFunding funding = loadSellerFundingPort.load(account.getSellerId());
        ReputationGrade grade = loadReputationPort.gradeOf(account.getSellerId());
        ScreeningResult result =
                cardLimitPolicy.screen(funding.sellerPayable(), funding.holdbackPayable(), grade);

        BigDecimal previousLimit = account.getMasterLimit();
        CardAccountStatus previousStatus = account.getStatus();

        // 탈락이어도 rescreen 을 먼저 태운다 — 산정 근거(LimitSnapshot)는 승인·탈락 모두 갱신돼야
        // "왜 정지됐나"를 사후에 재현할 수 있고, 한도는 Σ서브한도 하한 클램프를 그대로 따라야
        // 이미 발급된 카드가 통지 없이 죽지 않는다.
        LimitChangeResult change =
                account.rescreen(result.masterLimit(), result.snapshot(), currentSubLimitSum);
        boolean limitChanged = change.appliedLimit().compareTo(previousLimit) != 0;

        // 탈락은 한도 0 이 아니라 계정 정지로 표현한다. 한도만 0(또는 클램프된 값)으로 두면
        // 계정은 ACTIVE 인 채 카드가 사실상 무력화돼, 사용자도 상담원도 "왜 안 되는지" 알 수 없다.
        // 게다가 ACTIVE 로 남으면 남은 클램프 한도만큼 새 카드 발급까지 계속 통과한다.
        if (!result.approved()) {
            account.suspend();
        }
        boolean statusChanged = account.getStatus() != previousStatus;

        if (!limitChanged && !statusChanged) {
            // 조용한 날은 조용해야 한다 — 매일 전 계정에 이벤트를 내면 소비자 쪽에서
            // "진짜 바뀐 날"이 노이즈에 묻힌다. 저장도 생략해 version 을 튕기지 않는다.
            return false;
        }

        CardAccount saved = saveCardAccountPort.save(account);
        if (limitChanged) {
            publishCardEventPort.publishMasterLimitChanged(saved, previousLimit, change);
        }
        if (statusChanged) {
            publishCardEventPort.publishAccountStatusChanged(saved, previousStatus, result.rejectReason());
        }
        log.info("[CardLimitRecalc] accountId={} sellerId={} {} → {} clamped={} grade={} status={}",
                cardAccountId, account.getSellerId(), previousLimit.toPlainString(),
                change.appliedLimit().toPlainString(), change.clamped(), grade, account.getStatus());
        return true;
    }
}
