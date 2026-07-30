package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.in.GetWorkforceHistoryUseCase;
import github.lms.lemuel.company.application.port.out.LoadCompanyWorkforcePort;
import github.lms.lemuel.company.domain.CompanyWorkforce;
import github.lms.lemuel.company.domain.WorkforceHistory;
import github.lms.lemuel.company.domain.WorkplaceSeriesKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * 사업장 월별 시계열 조회 — 스냅샷 로드만 하고 정렬·증감 계산은 전부 도메인
 * ({@link WorkforceHistory})에 맡긴다.
 */
@Service
@Transactional(readOnly = true)
public class WorkforceHistoryQueryService implements GetWorkforceHistoryUseCase {

    private final LoadCompanyWorkforcePort loadCompanyWorkforcePort;

    public WorkforceHistoryQueryService(LoadCompanyWorkforcePort loadCompanyWorkforcePort) {
        this.loadCompanyWorkforcePort = loadCompanyWorkforcePort;
    }

    @Override
    public WorkforceHistory get(WorkplaceSeriesKey key) {
        List<CompanyWorkforce> snapshots = loadCompanyWorkforcePort.findSeries(key);
        if (snapshots.isEmpty()) {
            // 빈 시리즈를 도메인에 넘기면 IAE(400 계약)로 새므로, 미매칭은 여기서 404 계약으로 끊는다.
            throw new NoSuchElementException("해당 사업장 시계열을 찾을 수 없습니다: " + key.workplaceName());
        }
        return WorkforceHistory.of(snapshots);
    }
}
