package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.IngestReputationUseCase;
import github.lms.lemuel.card.application.port.out.SaveReputationPort;
import github.lms.lemuel.card.domain.ReputationGrade;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * company.reputation_changed 이벤트를 셀러별 평판 프로젝션으로 적재한다.
 * sellerIds 배열을 seller_id(String) 키로 팬아웃하는 것이 핵심 — {@link IngestReputationUseCase}
 * 클래스 주석 참조.
 */
@Service
public class ReputationProjectionService implements IngestReputationUseCase {

    private final SaveReputationPort saveReputationPort;

    public ReputationProjectionService(SaveReputationPort saveReputationPort) {
        this.saveReputationPort = saveReputationPort;
    }

    @Override
    @Transactional
    public void ingest(ReputationCommand command) {
        // 팬아웃 전에 검증한다 — 계약 밖 등급이면 셀러 일부만 갱신된 채 중단되는 대신
        // 한 건도 쓰지 않고 IAE 로 DLT 에 남는다. enum 이름으로 정규화해 저장하므로
        // 심사가 읽는 gradeOf 의 valueOf 는 절대 실패하지 않는다.
        ReputationGrade grade = ReputationGrade.from(command.grade());
        List<Long> sellerIds = command.sellerIds() == null ? List.of() : command.sellerIds();
        for (Long sellerId : sellerIds) {
            saveReputationPort.upsertGrade(String.valueOf(sellerId), grade.name());
        }
    }
}
