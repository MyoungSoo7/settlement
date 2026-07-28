package github.lms.lemuel.loan.adapter.out.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 기업대출 수동 REST(상환 repay)의 멱등 가드.
 *
 * <p>더블클릭·재전송 등으로 같은 논리적 상환이 두 번 반영돼 미상환잔액이 이중 차감되는 것을, 클라이언트가
 * 보낸 {@code Idempotency-Key} 를 PK 로 원자적으로 선점(claim)해 차단한다. {@code RepayCorporateLoanService}
 * 의 {@code findByIdForUpdate} 비관적 락은 동시 요청을 직렬화할 뿐, 앞선 상환이 커밋된 뒤 같은 요청이 다시
 * 도착하는 <b>순차 재제출</b>은 막지 못한다(두 번째가 남은 잔액을 다시 차감). 그래서 어댑터 계층의 키 선점을
 * 상환 실행 앞단에 둔다. 키가 없으면(레거시/키 미지정 호출) 멱등 처리를 건너뛴다 — 하위호환.
 *
 * <p>선점은 {@code INSERT ... ON CONFLICT DO NOTHING} (영향 행 수 기반)으로 수행한다. {@code saveAndFlush}
 * 후 예외를 잡는 방식은 {@code REQUIRES_NEW} 트랜잭션을 rollback-only 로 물들여 커밋 시점에
 * {@code UnexpectedRollbackException} 을 유발하므로, 예외 없이 승패를 가르는 upsert 를 쓴다
 * ({@link LoanManualOperationRecordRepository#insertIfAbsent}).
 *
 * <p>investment 의 {@code InvestmentManualIdempotencyGuard} 와 동형 — shared-common 승격 없이 서비스
 * 내부에 독립 구현한다(DB-per-service — 각 서비스가 자기 멱등 저장소를 소유).
 */
@Component
public class LoanManualIdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(LoanManualIdempotencyGuard.class);

    private final LoanManualOperationRecordRepository repository;

    public LoanManualIdempotencyGuard(LoanManualOperationRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * 키를 원자적으로 선점한다.
     *
     * @return 처음 보는 키면 {@code true}(호출자는 상환을 진행). 이미 선점된 키면 {@code false}(중복 —
     *     호출자는 409). 키가 null/blank 면 멱등 미적용({@code true}).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(String idempotencyKey, String endpoint, String operator) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return true;
        }
        int inserted = repository.insertIfAbsent(idempotencyKey, endpoint, operator, LocalDateTime.now());
        if (inserted == 0) {
            log.warn("[Idempotency] 중복 조작 차단 endpoint={} key={} operator={}",
                    endpoint, idempotencyKey, operator);
        }
        return inserted > 0;
    }
}
