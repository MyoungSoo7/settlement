package github.lms.lemuel.settlement.adapter.in.batch.confirm;

import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 정산 확정 청크 배치의 페이지 리더.
 *
 * <p>버퍼가 비면 {@code findConfirmableForUpdate(date, pageSize)} 로 다음 페이지를 비관적 락으로
 * 가져온다. 조회는 청크 트랜잭션 안에서 일어나므로 락이 청크 커밋까지 유지되고, 확정된 행은
 * DONE 으로 전이돼 다음 페이지에서 자연히 빠진다(상태 기반 진행 → offset 불필요, 재시작에도
 * 남은 REQUESTED 부터 자연 재개). 빈 페이지를 만나면 {@code null} 을 반환해 스텝을 종료한다.
 */
@Component
@StepScope
public class SettlementConfirmItemReader implements ItemReader<Settlement> {

    private final LoadSettlementPort loadSettlementPort;
    private final LocalDate targetDate;
    private final int pageSize;

    private final Deque<Settlement> buffer = new ArrayDeque<>();
    private boolean exhausted = false;

    public SettlementConfirmItemReader(
            LoadSettlementPort loadSettlementPort,
            @Value("#{jobParameters['targetDate']}") String targetDate,
            @Value("${app.settlement.confirm.chunk-size:100}") int pageSize) {
        this.loadSettlementPort = loadSettlementPort;
        // 방어적 파싱: 파라미터 누락(예: 의도치 않은 startup 실행) 시에도 NPE 대신 전일로 폴백.
        this.targetDate = (targetDate == null || targetDate.isBlank())
                ? LocalDate.now().minusDays(1)
                : LocalDate.parse(targetDate);
        this.pageSize = Math.max(1, pageSize);
    }

    @Override
    public Settlement read() {
        if (buffer.isEmpty() && !exhausted) {
            List<Settlement> page = loadSettlementPort.findConfirmableForUpdate(targetDate, pageSize);
            if (page.isEmpty() || page.size() < pageSize) {
                exhausted = true;
            }
            buffer.addAll(page);
        }
        return buffer.poll();
    }
}
