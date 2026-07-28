package github.lms.lemuel.recovery.application.service;

import github.lms.lemuel.recovery.application.port.in.EscalateStaleRecoveryUseCase;
import github.lms.lemuel.recovery.application.port.out.LoadSellerRecoveryPort;
import github.lms.lemuel.recovery.application.port.out.SaveSellerRecoveryPort;
import github.lms.lemuel.recovery.domain.SellerRecovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 정체 채권 이관 — OPEN 채권 중 마지막 활동이 유예 기간을 넘긴 건을 MANUAL_REQUIRED 로 이관한다.
 *
 * <p>실 운영: {@code RecoveryEscalationScheduler} 가 매일 새벽 호출. 홀드백 해제 배치와 동일하게
 * 한 번에 100 건씩 페이지 처리하여 락 경합을 최소화한다.
 */
@Service
@Transactional
public class EscalateStaleSellerRecoveryService implements EscalateStaleRecoveryUseCase {

    private static final Logger log = LoggerFactory.getLogger(EscalateStaleSellerRecoveryService.class);
    private static final int BATCH_SIZE = 100;

    private final LoadSellerRecoveryPort loadPort;
    private final SaveSellerRecoveryPort savePort;
    private final int manualEscalationDays;

    public EscalateStaleSellerRecoveryService(
            LoadSellerRecoveryPort loadPort,
            SaveSellerRecoveryPort savePort,
            @Value("${app.recovery.manual-escalation-days:30}") int manualEscalationDays) {
        this.loadPort = loadPort;
        this.savePort = savePort;
        this.manualEscalationDays = manualEscalationDays;
    }

    @Override
    public int escalateStaleOpenRecoveries(OffsetDateTime now) {
        OffsetDateTime cutoff = now.minusDays(manualEscalationDays);
        int totalEscalated = 0;
        while (true) {
            List<SellerRecovery> batch = loadPort.findStaleOpen(cutoff, BATCH_SIZE);
            if (batch.isEmpty()) break;

            for (SellerRecovery recovery : batch) {
                recovery.markManualRequired();
                savePort.save(recovery);
                totalEscalated++;
            }
            log.warn("[Recovery] 정체 채권 수기 이관: {} 건 (cutoff={})", batch.size(), cutoff);
            if (batch.size() < BATCH_SIZE) break;
        }
        return totalEscalated;
    }
}
