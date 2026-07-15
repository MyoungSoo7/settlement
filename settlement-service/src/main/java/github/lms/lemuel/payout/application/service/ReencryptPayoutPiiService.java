package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.payout.application.port.in.ReencryptPayoutPiiUseCase;
import github.lms.lemuel.payout.application.port.out.PayoutPiiBackfillPort;
import github.lms.lemuel.payout.domain.PayoutPiiBackfillReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 지급계좌 PII 재암호화 백필 서비스 — 페이지 루프 오케스트레이션만 담당하고, 실제 재암호화(엔티티 저장 경로)와
 * 페이지별 트랜잭션 커밋은 아웃바운드 포트에 위임한다.
 *
 * <p>루프는 포트의 {@code reencryptNextPage} 를 반복 호출한다. 각 호출은 프록시 빈의 독립 트랜잭션이라
 * 페이지 단위로 커밋된다(서비스 메서드 자체를 트랜잭션으로 감싸지 않는 이유 — 부분 성공 보존 + self-invocation 회피).
 * 재암호화는 평문 잔존을 단조 감소시키므로, 초기 잔존 건수로 페이지 상한을 산정해 무한 루프를 구조적으로 차단한다.
 */
@Service
public class ReencryptPayoutPiiService implements ReencryptPayoutPiiUseCase {

    private static final int MAX_PAGE_SIZE = 5000;

    private final PayoutPiiBackfillPort port;
    private final int defaultPageSize;

    public ReencryptPayoutPiiService(PayoutPiiBackfillPort port,
                                     @Value("${app.payout.pii-backfill.page-size:500}") int defaultPageSize) {
        this.port = port;
        this.defaultPageSize = defaultPageSize;
    }

    @Override
    public PayoutPiiBackfillReport reencryptLegacyPlaintext(Integer pageSizeOverride) {
        int pageSize = clampPageSize(pageSizeOverride);
        long initialRemaining = port.countLegacyPlaintext();
        // 안전 상한: 초기 잔존을 다 처리하고도 여유 2페이지. 재암호화가 잔존을 단조 감소시키므로
        // 정상 경로에선 done==0 으로 먼저 종료되고, 이 상한은 방어선(예: 실행 중 신규 유입)일 뿐이다.
        long maxPages = (initialRemaining / pageSize) + 2;

        long totalBackfilled = 0;
        int pagesCommitted = 0;
        while (pagesCommitted < maxPages) {
            int done = port.reencryptNextPage(pageSize);
            if (done == 0) {
                break;
            }
            totalBackfilled += done;
            pagesCommitted++;
        }

        long remaining = port.countLegacyPlaintext();
        return PayoutPiiBackfillReport.of(pageSize, totalBackfilled, remaining, pagesCommitted);
    }

    @Override
    public PayoutPiiBackfillReport remainingPlaintextCount() {
        return PayoutPiiBackfillReport.status(port.countLegacyPlaintext());
    }

    private int clampPageSize(Integer override) {
        if (override == null || override <= 0) {
            return defaultPageSize;
        }
        return Math.min(override, MAX_PAGE_SIZE);
    }
}
