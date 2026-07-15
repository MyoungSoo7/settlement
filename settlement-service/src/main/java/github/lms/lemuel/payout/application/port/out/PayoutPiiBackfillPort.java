package github.lms.lemuel.payout.application.port.out;

/**
 * 지급계좌 PII 재암호화 백필 아웃바운드 포트 — 평문 잔존 집계 + 페이지 단위 재암호화.
 *
 * <p>구현 어댑터는 각 페이지를 독립 트랜잭션으로 커밋한다(부분 성공 보존). 재암호화는 엔티티 저장 경로를
 * 태워 {@code PayoutFieldEncryptionConverter} 가 자동으로 암호화하게 한다.
 */
public interface PayoutPiiBackfillPort {

    /** 평문 잔존(raw 컬럼이 enc:v1 접두로 시작하지 않는) payout 행 수. */
    long countLegacyPlaintext();

    /**
     * 평문 잔존 행 최대 {@code pageSize} 건을 한 트랜잭션으로 재암호화하고 커밋한다.
     *
     * @return 이 페이지에서 재암호화한 행 수 (0 이면 더 이상 평문 잔존 없음 → 루프 종료 신호)
     */
    int reencryptNextPage(int pageSize);
}
