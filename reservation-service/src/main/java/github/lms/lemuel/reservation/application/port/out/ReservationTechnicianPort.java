package github.lms.lemuel.reservation.application.port.out;

/**
 * 배정 대상 시공기사의 자격을 검증하기 위한 아웃바운드 포트.
 *
 * <p>reservation 모듈이 user 도메인을 직접 import 하지 않도록 추상화한다.
 * Phase A: order-service 조립 루트의 in-process 어댑터(LoadUserPort 사용).
 * Phase B: Kafka 이벤트로 동기화되는 로컬 기사 프로젝션 어댑터.
 */
public interface ReservationTechnicianPort {

    /** 해당 userId 가 활성(APPROVED) 상태의 TECHNICIAN 이면 true. 존재하지 않으면 false. */
    boolean isAssignableTechnician(Long technicianId);
}
