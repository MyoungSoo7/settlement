package github.lms.lemuel.idempotency.application.port.in;

/**
 * 운영자 수동 조작(payout retry/cancel, chargeback accept/reject 등)의 멱등 선점 — idempotency 슬라이스의 제공 포트.
 *
 * <p>다른 슬라이스의 컨트롤러가 이 인터페이스만 알도록 두는 것이 이 타입의 존재 이유다.
 * 이전에는 chargeback·payout 의 web 어댑터가 idempotency 의 <b>영속 어댑터 구현체</b>를 직접 주입받았고,
 * 그 순간 멱등 레코드의 저장 방식(JPA 리포지토리·upsert 쿼리)이 두 슬라이스의 컴파일 의존이 됐다.
 */
public interface ClaimManualOperationUseCase {

    /**
     * 키를 원자적으로 선점한다.
     *
     * @param idempotencyKey 클라이언트가 보낸 {@code Idempotency-Key}. null/blank 면 멱등 미적용(하위호환)
     * @param endpoint 조작 식별용 엔드포인트 라벨(감사·로그용)
     * @param operator 조작 주체
     * @return 처음 보는 키면 {@code true}(호출자는 조작을 진행). 이미 선점된 키면 {@code false}(중복 — 호출자는 409)
     */
    boolean claim(String idempotencyKey, String endpoint, String operator);
}
