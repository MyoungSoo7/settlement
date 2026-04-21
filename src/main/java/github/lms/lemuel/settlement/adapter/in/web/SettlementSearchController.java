package github.lms.lemuel.settlement.adapter.in.web;

import github.lms.lemuel.settlement.adapter.in.web.response.SettlementPageResponse;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementSearchJdbcRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Settlement Search", description = "정산 검색/페이지네이션 API")
@RestController
@RequestMapping("/api/settlements")
@RequiredArgsConstructor
public class SettlementSearchController {

    private final SettlementSearchJdbcRepository searchRepository;

    @Operation(summary = "정산 검색",
            description = "주문자명/상품명/환불여부/상태/기간 조건으로 정산을 검색하고 페이지로 반환한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 파라미터")
    })
    @GetMapping("/search")
    public ResponseEntity<SettlementPageResponse> search(
            @Parameter(description = "주문자명(부분 일치)") @RequestParam(required = false) String ordererName,
            @Parameter(description = "상품명(부분 일치)") @RequestParam(required = false) String productName,
            @Parameter(description = "환불 여부") @RequestParam(required = false) Boolean isRefunded,
            @Parameter(description = "정산 상태") @RequestParam(required = false) String status,
            @Parameter(description = "검색 시작일 (yyyy-MM-dd)") @RequestParam(required = false) String startDate,
            @Parameter(description = "검색 종료일 (yyyy-MM-dd)") @RequestParam(required = false) String endDate,
            @Parameter(description = "페이지 (0부터)") @RequestParam(defaultValue = "0")  int page,
            @Parameter(description = "페이지 크기") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "정렬 필드") @RequestParam(defaultValue = "createdAt") String sortBy,
            @Parameter(description = "정렬 방향 (ASC/DESC)") @RequestParam(defaultValue = "DESC") String sortDirection
    ) {
        SettlementPageResponse response = searchRepository.search(
                ordererName, productName, isRefunded,
                status, startDate, endDate,
                page, size, sortBy, sortDirection);
        return ResponseEntity.ok(response);
    }
}
