package github.lms.lemuel.insurance.application.port.out;

import github.lms.lemuel.insurance.domain.GeneralPayout;

import java.util.List;

/**
 * 일반지급(GeneralPayout) 조회 포트.
 *
 * <p>{@link #findRequested()} 는 V10 의 부분 인덱스
 * {@code idx_general_payout_requested (WHERE status='REQUESTED')} 와 1:1 대응한다.
 */
public interface LoadGeneralPayoutPort {

    /** 일반지급 실행 배치 스캔 — REQUESTED 전건. */
    List<GeneralPayout> findRequested();

    /** 계약별 일반지급 내역 — 생성 순서(id) 정렬. */
    List<GeneralPayout> findByPolicyId(String policyId);
}
