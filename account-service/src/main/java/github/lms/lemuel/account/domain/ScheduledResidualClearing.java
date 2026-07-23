package github.lms.lemuel.account.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * cut-over 잔존 정산예정금(SETTLEMENT_SCHEDULED) 청산 계획(순수 도메인 계산) — ADR 0026 Option A 백필.
 *
 * <p>Option A 이전에는 정산 생성이 {@code DR SETTLEMENT_SCHEDULED / CR SELLER_PAYABLE}, 확정이
 * {@code DR SELLER_PAYABLE / CR SETTLEMENT_SCHEDULED} 로 전기돼 확정된 정산은 SETTLEMENT_SCHEDULED 가
 * 0 으로 상계됐다. cut-over 시점에 <b>생성됐지만 아직 확정되지 않은</b> 정산은 SETTLEMENT_SCHEDULED 에
 * 순차변 잔액을 남긴다. Option A 는 이 예정 클리어링 계정을 더 쓰지 않으므로, 셀러별 잔존 순차변을
 * {@code DR CASH / CR SETTLEMENT_SCHEDULED} 조정분개로 CASH 에 재분류해 계정을 0 으로 닫는다.
 *
 * <p><b>멱등(반복 실행 결과 불변):</b> 청산분개 자체가 SETTLEMENT_SCHEDULED 를 대변 상계하므로, 한 번
 * 청산된 셀러는 다음 계산에서 순잔액 0 이 되어 계획에 다시 오르지 않는다. 잔액 산정에 청산분개
 * (SETTLEMENT_SCHED_CLEARING)를 포함해 계산하므로 전량 재적재를 하지 않는다. (자연키 refId=sellerId
 * UNIQUE 는 동시 중복 실행에 대한 이중 방어.)
 */
public final class ScheduledResidualClearing {

    private ScheduledResidualClearing() {
    }

    /**
     * 전체 전표에서 셀러별 SETTLEMENT_SCHEDULED 순차변 잔액(&gt; 0)에 대한 청산분개 목록을 만든다.
     * 순잔액이 0 이하인 셀러(이미 상계됨)는 계획에서 제외한다 → 반복 실행 시 빈 계획(불변).
     */
    public static List<AccountEntry> plan(List<AccountEntry> entries) {
        Map<String, BigDecimal> residualBySeller = new LinkedHashMap<>();
        for (AccountEntry e : entries) {
            if (e.getOwnerType() != OwnerType.SELLER) {
                continue;
            }
            if (e.getDebitAccount() == GlAccount.SETTLEMENT_SCHEDULED) {
                residualBySeller.merge(e.getOwnerId(), e.getAmount(), BigDecimal::add);
            }
            if (e.getCreditAccount() == GlAccount.SETTLEMENT_SCHEDULED) {
                residualBySeller.merge(e.getOwnerId(), e.getAmount().negate(), BigDecimal::add);
            }
        }

        List<AccountEntry> plan = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> en : residualBySeller.entrySet()) {
            if (en.getValue().signum() > 0) {
                plan.add(AccountEntry.settlementScheduledClearing(en.getKey(), en.getValue()));
            }
        }
        return plan;
    }
}
