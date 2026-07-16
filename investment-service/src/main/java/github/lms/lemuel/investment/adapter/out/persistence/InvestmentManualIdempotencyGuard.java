package github.lms.lemuel.investment.adapter.out.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 투자 수동 REST(주문 신청 place, 집행 execute, 취소 cancel)의 멱등 가드.
 *
 * <p>더블클릭·재전송 등으로 같은 논리적 조작이 두 번 실행되는 것을, 클라이언트가 보낸
 * {@code Idempotency-Key} 를 PK 로 원자적으로 선점(claim)해 차단한다. 주문 행 {@code @Version} 낙관적
 * 락만으로는 두 요청이 같은 시작 상태를 관측하는 전이 직전의 짧은 경합 창을 완전히 막지 못하므로(집행은
 * 이중 이벤트 발행, 신청은 중복 주문 생성으로 이어질 수 있다), 어댑터 계층의 키 선점을 앞단에 둔다.
 * 키가 없으면(레거시/키 미지정 호출) 멱등 처리를 건너뛴다 — 하위호환.
 *
 * <p>선점은 {@code INSERT ... ON CONFLICT DO NOTHING} (영향 행 수 기반)으로 수행한다. {@code saveAndFlush}
 * 후 예외를 잡는 방식은 {@code REQUIRES_NEW} 트랜잭션을 rollback-only 로 물들여 커밋 시점에
 * {@code UnexpectedRollbackException} 을 유발하므로, 예외 없이 승패를 가르는 upsert 를 쓴다
 * ({@link InvestmentManualOperationRecordRepository#insertIfAbsent}).
 *
 * <p>settlement 의 {@code ManualIdempotencyGuard} 와 동형이나 shared-common 승격 없이 서비스 내부에
 * 독립 구현한다(DB-per-service — 각 서비스가 자기 멱등 저장소를 소유).
 */
@Component
public class InvestmentManualIdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(InvestmentManualIdempotencyGuard.class);

    private final InvestmentManualOperationRecordRepository repository;

    public InvestmentManualIdempotencyGuard(InvestmentManualOperationRecordRepository repository) {
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
        int inserted = repository.insertIfAbsent(idempotencyKey, endpoint, operator, LocalDateTime.now());
        if (inserted == 0) {
            log.warn("[Idempotency] 중복 조작 차단 endpoint={} key={} operator={}",
                    endpoint, idempotencyKey, operator);
        }
        return inserted > 0;
    }
}
