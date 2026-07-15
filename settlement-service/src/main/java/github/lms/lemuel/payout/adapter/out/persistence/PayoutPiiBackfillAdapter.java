package github.lms.lemuel.payout.adapter.out.persistence;

import github.lms.lemuel.payout.application.port.out.PayoutPiiBackfillPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 지급계좌 PII 재암호화 백필 어댑터 — 평문 잔존 집계 + 페이지 단위 재암호화.
 *
 * <p>재암호화 경로: nativeQuery 로 평문 잔존 id 를 뽑아 → 엔티티를 로드(컨버터가 평문/암호문을 모두 평문으로
 * 읽음) → {@link PayoutJpaEntity#markPiiForReencryption} 로 dirty 화 → flush 시 전 컬럼 UPDATE 가
 * PII 컬럼을 컨버터로 재암호화한다. SQL 로 직접 암호화할 수 없어 반드시 엔티티 저장 경로를 태운다.
 *
 * <p>{@code reencryptNextPage} 는 페이지마다 독립 트랜잭션(빈 프록시 경계)으로 커밋된다 — 대량 행에서도
 * 부분 성공이 보존되고 락 보유 시간이 짧다.
 */
@Component
public class PayoutPiiBackfillAdapter implements PayoutPiiBackfillPort {

    private final SpringDataPayoutRepository repository;

    public PayoutPiiBackfillAdapter(SpringDataPayoutRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public long countLegacyPlaintext() {
        return repository.countLegacyPlaintext();
    }

    @Override
    @Transactional
    public int reencryptNextPage(int pageSize) {
        List<Long> ids = repository.findLegacyPlaintextIds(Math.max(1, pageSize));
        if (ids.isEmpty()) {
            return 0;
        }
        LocalDateTime touchedAt = LocalDateTime.now();
        for (Long id : ids) {
            repository.findById(id).ifPresent(entity -> entity.markPiiForReencryption(touchedAt));
        }
        // 명시 flush 로 이 페이지의 재암호화 UPDATE 를 트랜잭션 커밋 전에 확정한다.
        repository.flush();
        return ids.size();
    }
}
