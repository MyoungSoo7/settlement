package github.lms.lemuel.company.application.port.in;

import github.lms.lemuel.company.domain.WorkforceComparison;
import github.lms.lemuel.company.domain.WorkplaceKey;

/**
 * 국민연금 사업장 단건 상세 조회 — 같은 기준월의 동종 업종·동일 지역 집단과 비교한다.
 *
 * <p>목록 검색({@link GetCompanyWorkforceUseCase})과는 별개 유스케이스다 — 목록 응답 스키마는
 * 변경하지 않는다.
 */
public interface GetWorkforceComparisonUseCase {

    /**
     * @throws java.util.NoSuchElementException 복합키가 어느 레코드와도 매칭되지 않을 때
     */
    WorkforceComparison get(WorkplaceKey key);
}
