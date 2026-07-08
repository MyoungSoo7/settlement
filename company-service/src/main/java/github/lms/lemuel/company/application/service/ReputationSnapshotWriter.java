package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.out.LoadReputationPort;
import github.lms.lemuel.company.application.port.out.PublishReputationEventPort;
import github.lms.lemuel.company.application.port.out.SaveReputationPort;
import github.lms.lemuel.company.domain.ReputationGrade;
import github.lms.lemuel.company.domain.ReputationScore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 평판 스냅샷 저장 + 등급 변동 이벤트 발행을 <b>한 트랜잭션</b>으로 묶는 쓰기 단위 (ADR 0023 Phase 3).
 *
 * <p>스냅샷 INSERT 와 Outbox 기록이 원자적이어야(둘 다 커밋/롤백) 이벤트 유실·유령 이벤트가 없다.
 * {@link ReputationRecalcService} 와 별도 빈으로 둔 이유는 {@code recalcAll} 의 기업별 반복이
 * 프록시를 거쳐 각 기업마다 독립 트랜잭션을 얻게 하기 위함이다(자기호출 시 @Transactional 무효 회피).
 *
 * <p>발행 조건: 새 스냅샷이 실제 저장됐고(하루 1건, INSERT-only) 등급이 직전과 다를 때
 * (최초 스냅샷 = 직전 없음도 변동으로 간주 → loan 이 초기 등급을 최소 1회 학습).
 */
@Service
public class ReputationSnapshotWriter {

    private final LoadReputationPort loadReputationPort;
    private final SaveReputationPort saveReputationPort;
    private final PublishReputationEventPort publishReputationEventPort;

    public ReputationSnapshotWriter(LoadReputationPort loadReputationPort,
                                    SaveReputationPort saveReputationPort,
                                    PublishReputationEventPort publishReputationEventPort) {
        this.loadReputationPort = loadReputationPort;
        this.saveReputationPort = saveReputationPort;
        this.publishReputationEventPort = publishReputationEventPort;
    }

    /**
     * @return 저장됐으면 true, 오늘자 스냅샷이 이미 있어(또는 동시 저장 레이스) 건너뛰면 false.
     */
    @Transactional
    public boolean writeIfChanged(ReputationScore score) {
        ReputationGrade previousGrade = loadReputationPort.findLatest(score.stockCode())
                .map(ReputationScore::grade)
                .orElse(null);
        if (!saveReputationPort.saveIfAbsent(score)) {
            return false;
        }
        if (previousGrade == null || previousGrade != score.grade()) {
            publishReputationEventPort.publishReputationChanged(score, previousGrade);
        }
        return true;
    }
}
