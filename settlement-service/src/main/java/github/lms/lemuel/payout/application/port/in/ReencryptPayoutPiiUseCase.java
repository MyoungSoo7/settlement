package github.lms.lemuel.payout.application.port.in;

import github.lms.lemuel.payout.domain.PayoutPiiBackfillReport;

/**
 * 지급계좌 PII 재암호화 백필 유스케이스 — 관리자 트리거형 운영 작업.
 *
 * <p>레거시 평문(enc:v1 접두 없음) 지급계좌 행을 페이지 단위로 재저장해 암호문으로 강제 전환하고,
 * 실행 후 평문 잔존 건수를 함께 반환한다(잔존 검증). 탐지·전환만 담당하며 스킴/키 교체는 별도 경로.
 */
public interface ReencryptPayoutPiiUseCase {

    /**
     * 레거시 평문 행을 페이지 단위로 재암호화한다. 각 페이지는 독립 트랜잭션으로 커밋되며,
     * 재암호화가 평문 잔존을 단조 감소시키므로 안전 상한(초기 잔존 기반) 내에서 소진까지 반복한다.
     *
     * @param pageSizeOverride 페이지 크기 오버라이드(null/비양수면 기본값 500). 상한 초과 시 상한으로 클램프.
     * @return 재암호화 건수·페이지 수·평문 잔존 건수·완료 여부
     */
    PayoutPiiBackfillReport reencryptLegacyPlaintext(Integer pageSizeOverride);

    /** 백필 실행 없이 현재 평문 잔존 건수만 조회(사전/사후 검증용). */
    PayoutPiiBackfillReport remainingPlaintextCount();
}
