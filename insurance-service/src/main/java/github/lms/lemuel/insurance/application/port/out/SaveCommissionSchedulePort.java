package github.lms.lemuel.insurance.application.port.out;

import github.lms.lemuel.insurance.domain.CommissionSchedule;

import java.util.List;

/**
 * 수수료 회차(CommissionSchedule) 저장 포트.
 */
public interface SaveCommissionSchedulePort {

    /** 단건 저장 — 신규 INSERT 또는 상태 전이 반영. */
    CommissionSchedule save(CommissionSchedule schedule);

    /** 일괄 저장 — 초년도 12회 스케줄 확정 등. */
    List<CommissionSchedule> saveAll(List<CommissionSchedule> schedules);
}
