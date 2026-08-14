package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.MarkDelinquentStatementsUseCase;
import github.lms.lemuel.card.application.port.out.LoadCardStatementPort;
import github.lms.lemuel.card.domain.CardStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 연체 명세서 전이 배치 서비스 — 배치 조율(orchestration) 담당.
 *
 * <h3>트랜잭션 경계</h3>
 * 1건 처리는 {@link DelinquentStatementProcessor}(별도 빈 · REQUIRES_NEW)에 위임한다.
 * 이 클래스가 직접 DB 작업을 수행하면 자기 호출(self-invocation)로 인해 트랜잭션이
 * 메서드별로 분리되지 않고 배치 전체가 단일 트랜잭션이 되어 한 건 실패 시 전체 롤백된다.
 *
 * <h3>처리 흐름</h3>
 * <ol>
 *   <li>만기 경과 + 미납 명세서 조회</li>
 *   <li>명세서마다 {@link DelinquentStatementProcessor#process(CardStatement)} 호출</li>
 *   <li>1건 실패는 삼키고 나머지를 계속 처리한다(fail-open)</li>
 * </ol>
 */
@Service
public class MarkDelinquentStatementsService implements MarkDelinquentStatementsUseCase {

    private static final Logger log = LoggerFactory.getLogger(MarkDelinquentStatementsService.class);

    private final LoadCardStatementPort loadCardStatementPort;
    private final DelinquentStatementProcessor processor;

    public MarkDelinquentStatementsService(LoadCardStatementPort loadCardStatementPort,
                                           DelinquentStatementProcessor processor) {
        this.loadCardStatementPort = loadCardStatementPort;
        this.processor = processor;
    }

    @Override
    public int markDelinquent(LocalDate today) {
        List<CardStatement> overdue = loadCardStatementPort.findOverdueAndUnpaid(today);
        log.info("[Delinquency] 연체 후보 {}건 (기준일={})", overdue.size(), today);

        int count = 0;
        for (CardStatement statement : overdue) {
            try {
                processor.process(statement);   // ← 별도 빈 호출 → 프록시 인터셉션 → REQUIRES_NEW 트랜잭션
                count++;
            } catch (RuntimeException e) {
                log.error("[Delinquency] 연체 처리 실패 statementId={} — 건너뜀 (fail-open)",
                        statement.getId(), e);
            }
        }
        log.info("[Delinquency] 연체 처리 완료 {}건", count);
        return count;
    }
}
