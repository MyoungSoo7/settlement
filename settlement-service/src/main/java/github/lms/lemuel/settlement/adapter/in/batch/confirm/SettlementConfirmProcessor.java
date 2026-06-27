package github.lms.lemuel.settlement.adapter.in.batch.confirm;

import github.lms.lemuel.settlement.domain.Settlement;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * 정산 확정 청크 배치의 프로세서 — REQUESTED → DONE 도메인 전이만 수행한다.
 *
 * <p>락 조회가 방어적으로 비대상(이미 DONE 등)을 반환해도 {@code null} 을 돌려 청크에서 제외한다.
 * 저장·이벤트·원장 적재 같은 부수효과는 {@link SettlementConfirmItemWriter} 가 청크 단위로 담당한다.
 */
@Component
public class SettlementConfirmProcessor implements ItemProcessor<Settlement, Settlement> {

    @Override
    public Settlement process(Settlement settlement) {
        if (!settlement.isPending()) {
            return null; // 방어적 필터 — 이미 확정/취소된 행은 건너뜀
        }
        settlement.confirm(); // REQUESTED → PROCESSING → DONE
        return settlement;
    }
}
