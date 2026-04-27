package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.ConfirmDailySettlementsUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementEventPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 일일 정산 확정 서비스
 * Spring Batch에 의존하지 않는 순수 비즈니스 로직
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmDailySettlementsService implements ConfirmDailySettlementsUseCase {

    private final LoadSettlementPort loadSettlementPort;
    private final SaveSettlementPort saveSettlementPort;
    private final PublishSettlementEventPort publishSettlementEventPort;

    @Override
    @Transactional
    public ConfirmSettlementResult confirmDailySettlements(ConfirmSettlementCommand command) {
        log.info("정산 확정 시작: targetDate={}", command.targetDate());

        // 1. 대상 정산 조회
        List<Settlement> settlements = loadSettlementPort.findBySettlementDate(command.targetDate());

        int totalSettlements = settlements.size();
        int confirmedCount = 0;
        List<Long> confirmedSettlementIds = new ArrayList<>();

        // 2. 정산 확정
        for (Settlement settlement : settlements) {
            if (settlement.isPending()) {
                settlement.confirm(); // 도메인 로직 호출
                Settlement saved = saveSettlementPort.save(settlement);
                confirmedSettlementIds.add(saved.getId());
                confirmedCount++;
            }
        }

        log.info("정산 확정 완료: confirmedCount={}, totalSettlements={}", confirmedCount, totalSettlements);

        // 3. 이벤트 발행
        if (!confirmedSettlementIds.isEmpty()) {
            publishSettlementEventPort.publishSettlementConfirmedEvent(confirmedSettlementIds);
        }

        return new ConfirmSettlementResult(confirmedCount, totalSettlements);
    }
}
