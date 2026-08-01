package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.IngestReputationUseCase;
import github.lms.lemuel.card.application.port.out.SaveReputationPort;
import github.lms.lemuel.card.domain.ReputationGrade;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 평판 프로젝션 적재 서비스 — 기업 단위 이벤트를 셀러 단위 등급으로 팬아웃한다.
 *
 * <p>등급 문자열이 계약(A~E) 밖이면 {@link IllegalArgumentException} 으로 즉시 DLT 격리된다.
 * 팬아웃 전체가 컨슈머 트랜잭션 하나에 묶여, 일부 셀러만 갱신된 어중간한 상태를 남기지 않는다.
 */
@Service
@Transactional
public class ReputationProjectionService implements IngestReputationUseCase {

    private final SaveReputationPort saveReputationPort;

    public ReputationProjectionService(SaveReputationPort saveReputationPort) {
        this.saveReputationPort = saveReputationPort;
    }

    @Override
    public void ingest(ReputationCommand command) {
        ReputationGrade grade = ReputationGrade.valueOf(command.grade());
        for (Long sellerId : command.sellerIds()) {
            saveReputationPort.upsertGrade(String.valueOf(sellerId), grade);
        }
    }
}
