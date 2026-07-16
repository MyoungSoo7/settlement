package github.lms.lemuel.idempotency.adapter.out.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 운영자 수동 REST(payout retry/cancel, chargeback accept/reject 등)의 멱등 가드.
 *
 * <p>더블클릭·재전송 등으로 같은 논리적 조작이 두 번 실행되는 것을, 클라이언트가 보낸
 * {@code Idempotency-Key} 를 PK 로 원자적으로 선점(claim)해 차단한다. 상태머신 가드만으로는 상태 전이
 * 직전의 짧은 경합 창을 완전히 막지 못하므로(두 요청이 같은 시작 상태를 관측), 어댑터 계층의 키 선점을
 * 앞단에 둔다. 키가 없으면(레거시/키 미지정 호출) 멱등 처리를 건너뛴다 — 하위호환.
 */
@Component
public class ManualIdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(ManualIdempotencyGuard.class);

    private final ManualOperationRecordRepository repository;

    public ManualIdempotencyGuard(ManualOperationRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * 키를 원자적으로 선점한다.
     *
     * @return 처음 보는 키면 {@code true}(호출자는 조작을 진행). 이미 선점된 키면 {@code false}(중복 —
     *     호출자는 409). 키가 null/blank 면 멱등 미적용({@code true}).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(String idempotencyKey, String endpoint, String operator) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return true;
        }
        // saveAndFlush+catch 는 유니크 위반 시 REQUIRES_NEW 트랜잭션을 rollback-only 로 물들여
        // 커밋 시점 UnexpectedRollbackException(→500)을 유발한다. 예외 없는 원자 upsert 로 승패를 가른다.
        int inserted = repository.insertIfAbsent(idempotencyKey, endpoint, operator, Instant.now());
        if (inserted == 0) {
            log.warn("[Idempotency] 중복 조작 차단 endpoint={} key={} operator={}",
                    endpoint, idempotencyKey, operator);
        }
        return inserted > 0;
    }
}
