package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.application.port.out.LoadCommissionSchedulePort;
import github.lms.lemuel.insurance.application.port.out.SaveCommissionSchedulePort;
import github.lms.lemuel.insurance.domain.CommissionSchedule;
import github.lms.lemuel.insurance.domain.CommissionStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Component
public class CommissionSchedulePersistenceAdapter
        implements LoadCommissionSchedulePort, SaveCommissionSchedulePort {

    private final SpringDataCommissionScheduleRepository repository;

    public CommissionSchedulePersistenceAdapter(SpringDataCommissionScheduleRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<CommissionSchedule> findDueScheduledOnOrBefore(LocalDate date) {
        return repository.findByStatusAndDueDateLessThanEqual(CommissionStatus.SCHEDULED, date)
                .stream().map(CommissionScheduleJpaEntity::toDomain).toList();
    }

    @Override
    public List<CommissionSchedule> findByPolicyIdAndStatus(String policyId, CommissionStatus status) {
        return repository.findByPolicyIdAndStatus(UUID.fromString(policyId), status)
                .stream().map(CommissionScheduleJpaEntity::toDomain).toList();
    }

    @Override
    public BigDecimal sumPaidTotalByPolicyId(String policyId) {
        return repository.sumPaidTotalByPolicyId(UUID.fromString(policyId));
    }

    @Override
    public List<FcPaidSummary> summarizePaidByFcInMonth(YearMonth month) {
        // paid_at 은 TIMESTAMPTZ — 월 경계를 KST 자정 기준 Instant 반개구간 [1일, 익월 1일) 으로 변환.
        // CommissionScheduleJpaEntity 의 지급일 매핑(KST 자정)과 동일 출처라 경계 유실이 없다.
        ZoneId kst = ZoneId.of("Asia/Seoul");
        Instant from = month.atDay(1).atStartOfDay(kst).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(kst).toInstant();
        return repository.summarizePaidByFcBetween(from, to);
    }

    @Override
    public CommissionSchedule save(CommissionSchedule schedule) {
        if (schedule.getId() == null) {
            return repository.saveAndFlush(CommissionScheduleJpaEntity.fromDomain(schedule)).toDomain();
        }
        CommissionScheduleJpaEntity entity = repository.findById(schedule.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "존재하지 않는 수수료 회차 행에 전이 결과를 반영할 수 없습니다: id=" + schedule.getId()));
        entity.applyTransitionResult(schedule);
        return repository.saveAndFlush(entity).toDomain();
    }

    @Override
    public List<CommissionSchedule> saveAll(List<CommissionSchedule> schedules) {
        return schedules.stream().map(this::save).toList();
    }
}
