package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.adapter.in.web.dto.CompanyWorkforceResponse;
import github.lms.lemuel.company.adapter.in.web.dto.PageResponse;
import github.lms.lemuel.company.adapter.in.web.dto.WorkforceComparisonResponse;
import github.lms.lemuel.company.adapter.in.web.dto.WorkforceHistoryResponse;
import github.lms.lemuel.company.application.port.in.GetCompanyWorkforceUseCase;
import github.lms.lemuel.company.application.port.in.GetWorkforceComparisonUseCase;
import github.lms.lemuel.company.application.port.in.GetWorkforceHistoryUseCase;
import github.lms.lemuel.company.domain.WorkplaceKey;
import github.lms.lemuel.company.domain.WorkplaceSeriesKey;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 국민연금 사업장가입자 공개데이터 기반 인원수/추정연봉 내부 조회 (서울 소프트웨어·IT 서비스 사업장 — 기존
 * {@code /api/company/companies}(상장사 stockCode 체계)와 무관한 독립 검색).
 * 세 GET 경로 모두 JWT {@code ADMIN} 또는 {@code MANAGER} 권한이 필요하다.
 */
@RestController
@RequestMapping("/api/company/workforce")
public class CompanyWorkforceController {

    private final GetCompanyWorkforceUseCase getCompanyWorkforceUseCase;
    private final GetWorkforceComparisonUseCase getWorkforceComparisonUseCase;
    private final GetWorkforceHistoryUseCase getWorkforceHistoryUseCase;

    public CompanyWorkforceController(GetCompanyWorkforceUseCase getCompanyWorkforceUseCase,
                                      GetWorkforceComparisonUseCase getWorkforceComparisonUseCase,
                                      GetWorkforceHistoryUseCase getWorkforceHistoryUseCase) {
        this.getCompanyWorkforceUseCase = getCompanyWorkforceUseCase;
        this.getWorkforceComparisonUseCase = getWorkforceComparisonUseCase;
        this.getWorkforceHistoryUseCase = getWorkforceHistoryUseCase;
    }

    @GetMapping
    public ResponseEntity<PageResponse<CompanyWorkforceResponse>> search(
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        GetCompanyWorkforceUseCase.WorkforcePage result = getCompanyWorkforceUseCase.search(name, page, size);
        return ResponseEntity.ok(new PageResponse<>(
                result.content().stream().map(CompanyWorkforceResponse::from).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages()));
    }

    /**
     * 단건 상세 + 동종 업종·동일 지역 집단 대비 비교.
     *
     * <p>식별자는 내부 id 가 아니라 업무 복합키다. 실제 데이터에 따옴표·느낌표가 든 사업장명이 있어
     * path variable 로는 안전하지 않으므로 <b>query parameter</b> 로 받아 표준 URL 인코딩에 맡긴다.
     *
     * <p>파라미터는 {@code required = false} 로 받아 누락 검증까지 {@link WorkplaceKey} 에서 한다 —
     * 프레임워크 기본 오류 대신 이 서비스의 오류 본문({@code {"message": ...}})으로 일관되게 응답하려는 것이다.
     * 검증 위반은 400, 복합키 미매칭은 404 (둘 다 {@code GlobalExceptionHandler} 경유).
     * 같은 파라미터가 중복으로 오면 Spring 이 콤마로 합치므로 어느 레코드와도 매칭되지 않아 404 가 된다.
     */
    @GetMapping("/detail")
    public ResponseEntity<WorkforceComparisonResponse> detail(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String bizRegNoPrefix,
            @RequestParam(required = false) String snapshotMonth) {
        WorkplaceKey key = WorkplaceKey.of(name, bizRegNoPrefix, snapshotMonth);
        return ResponseEntity.ok(WorkforceComparisonResponse.from(getWorkforceComparisonUseCase.get(key)));
    }

    /**
     * 월별 시계열 — 상세와 달리 기준월이 없는 2요소 키(사업장명+앞6자리)다. 파라미터 수령·검증
     * 방식은 {@link #detail} 과 동일한 이유로 query parameter + 도메인 키 검증이다.
     */
    @GetMapping("/history")
    public ResponseEntity<WorkforceHistoryResponse> history(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String bizRegNoPrefix) {
        WorkplaceSeriesKey key = WorkplaceSeriesKey.of(name, bizRegNoPrefix);
        return ResponseEntity.ok(WorkforceHistoryResponse.from(key, getWorkforceHistoryUseCase.get(key)));
    }
}
