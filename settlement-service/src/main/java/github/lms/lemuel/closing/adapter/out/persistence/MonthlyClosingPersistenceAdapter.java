package github.lms.lemuel.closing.adapter.out.persistence;

import github.lms.lemuel.closing.application.port.out.LoadMonthlyClosingPort;
import github.lms.lemuel.closing.application.port.out.SaveMonthlyClosingPort;
import github.lms.lemuel.closing.domain.ClosingRunStatus;
import github.lms.lemuel.closing.domain.ClosingTotals;
import github.lms.lemuel.closing.domain.MonthlyClosingRun;
import github.lms.lemuel.closing.domain.SellerMonthlyClosing;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MonthlyClosingPersistenceAdapter implements LoadMonthlyClosingPort, SaveMonthlyClosingPort {

    private final SpringDataMonthlyClosingRunRepository runRepository;
    private final SpringDataSellerMonthlyClosingRepository martRepository;

    @Override
    public Optional<MonthlyClosingRun> findRun(YearMonth period) {
        return runRepository.findByPeriodYm(period.toString()).map(this::toDomain);
    }

    @Override
    public List<SellerMonthlyClosing> findMart(YearMonth period) {
        return martRepository.findByPeriodYmOrderBySellerIdAsc(period.toString()).stream()
                .map(this::toDomain)
                .toList();
    }

    /** 마트 교체(delete+insert) + run upsert 를 한 트랜잭션으로 — 반쪽 마감 방지. */
    @Override
    @Transactional
    public MonthlyClosingRun saveCompleted(MonthlyClosingRun run, List<SellerMonthlyClosing> rows) {
        martRepository.deleteByPeriodYm(run.getPeriodYm());
        martRepository.saveAll(rows.stream().map(this::toEntity).toList());
        return upsertRun(run);
    }

    @Override
    @Transactional
    public MonthlyClosingRun saveRun(MonthlyClosingRun run) {
        return upsertRun(run);
    }

    private MonthlyClosingRun upsertRun(MonthlyClosingRun run) {
        MonthlyClosingRunJpaEntity entity = runRepository.findByPeriodYm(run.getPeriodYm())
                .orElseGet(MonthlyClosingRunJpaEntity::new);
        entity.setPeriodYm(run.getPeriodYm());
        entity.setStatus(run.getStatus().name());
        entity.setTriggeredBy(run.getTriggeredBy());
        entity.setStartedAt(run.getStartedAt());
        entity.setFinishedAt(run.getFinishedAt());
        entity.setSellerCount(run.getSellerCount());
        entity.setSettlementCount(run.getSettlementCount());
        entity.setUnmappedCount(run.getUnmappedCount());
        entity.setPendingCount(run.getPendingCount());
        ClosingTotals totals = run.getTotals();
        entity.setTotalGross(totals != null ? totals.grossAmount() : null);
        entity.setTotalRefunded(totals != null ? totals.refundedAmount() : null);
        entity.setTotalCommission(totals != null ? totals.commissionAmount() : null);
        entity.setTotalHoldback(totals != null ? totals.holdbackAmount() : null);
        entity.setTotalNet(totals != null ? totals.netAmount() : null);
        entity.setFailureReason(run.getFailureReason());
        entity.setCreatedAt(run.getCreatedAt() != null ? run.getCreatedAt() : OffsetDateTime.now(ZoneOffset.UTC));
        return toDomain(runRepository.save(entity));
    }

    private MonthlyClosingRun toDomain(MonthlyClosingRunJpaEntity entity) {
        ClosingTotals totals = entity.getTotalGross() == null ? null
                : ClosingTotals.of(entity.getTotalGross(), entity.getTotalRefunded(),
                        entity.getTotalCommission(), entity.getTotalHoldback(), entity.getTotalNet());
        return MonthlyClosingRun.rehydrate(
                entity.getId(),
                YearMonth.parse(entity.getPeriodYm()),
                ClosingRunStatus.valueOf(entity.getStatus()),
                entity.getTriggeredBy(),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getSellerCount(),
                entity.getSettlementCount(),
                entity.getUnmappedCount(),
                entity.getPendingCount(),
                totals,
                entity.getFailureReason(),
                entity.getCreatedAt());
    }

    private SellerMonthlyClosing toDomain(SellerMonthlyClosingJpaEntity entity) {
        return SellerMonthlyClosing.of(
                YearMonth.parse(entity.getPeriodYm()),
                entity.getSellerId(),
                entity.getSettlementCount(),
                entity.getGrossAmount(),
                entity.getRefundedAmount(),
                entity.getCommissionAmount(),
                entity.getHoldbackAmount(),
                entity.getNetAmount());
    }

    private SellerMonthlyClosingJpaEntity toEntity(SellerMonthlyClosing row) {
        SellerMonthlyClosingJpaEntity entity = new SellerMonthlyClosingJpaEntity();
        entity.setPeriodYm(row.getPeriodYm());
        entity.setSellerId(row.getSellerId());
        entity.setSettlementCount(row.getSettlementCount());
        entity.setGrossAmount(row.getGrossAmount());
        entity.setRefundedAmount(row.getRefundedAmount());
        entity.setCommissionAmount(row.getCommissionAmount());
        entity.setHoldbackAmount(row.getHoldbackAmount());
        entity.setNetAmount(row.getNetAmount());
        return entity;
    }
}
