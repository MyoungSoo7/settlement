package github.lms.lemuel.company.application.port.in;

import github.lms.lemuel.company.domain.WorkforceHistory;
import github.lms.lemuel.company.domain.WorkplaceSeriesKey;

/**
 * 국민연금 사업장 월별 시계열 조회 — 같은 사업장 키(사업장명+앞6자리)의 전 스냅샷을 월 오름차순으로.
 *
 * <p>단건 상세({@link GetWorkforceComparisonUseCase})·목록 검색과는 별개 유스케이스다 — 기존 응답
 * 스키마는 변경하지 않는다(시드 AC-5).
 */
public interface GetWorkforceHistoryUseCase {

    /**
     * @throws java.util.NoSuchElementException 시계열 키가 어느 레코드와도 매칭되지 않을 때
     */
    WorkforceHistory get(WorkplaceSeriesKey key);
}
