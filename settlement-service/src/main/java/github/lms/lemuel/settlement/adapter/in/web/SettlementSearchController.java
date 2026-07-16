package github.lms.lemuel.settlement.adapter.in.web;

import github.lms.lemuel.settlement.adapter.in.web.response.SettlementPageResponse;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementSearchJdbcRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDate;

@Tag(name = "Settlement Search", description = "정산 검색/페이지네이션 API")
@RestController
@RequestMapping("/api/settlements")
@RequiredArgsConstructor
@Validated
public class SettlementSearchController {

    private final SettlementSearchJdbcRepository searchRepository;

    @Operation(summary = "정산 검색",
            description = "주문자명/상품명/환불여부/상태/기간 조건으로 정산을 검색하고 페이지로 반환한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 파라미터(페이지·크기 범위, 날짜 형식/역전)")
    })
    @GetMapping("/search")
    public ResponseEntity<SettlementPageResponse> search(
            @Parameter(description = "주문자명(부분 일치)") @RequestParam(required = false) String ordererName,
            @Parameter(description = "상품명(부분 일치)") @RequestParam(required = false) String productName,
            @Parameter(description = "환불 여부") @RequestParam(required = false) Boolean isRefunded,
            @Parameter(description = "정산 상태") @RequestParam(required = false) String status,
            @Parameter(description = "검색 시작일 (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "검색 종료일 (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "페이지 (0부터)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기 (1~200)") @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @Parameter(description = "정렬 필드") @RequestParam(defaultValue = "createdAt") String sortBy,
            @Parameter(description = "정렬 방향 (ASC/DESC)") @RequestParam(defaultValue = "DESC") String sortDirection
    ) {
        // 형식 오류(비-ISO 날짜, 비정수 page/size)와 범위 위반(@Min/@Max)은 각각 타입 변환 실패와
        // 메서드 검증에서 400 으로 매핑된다. 여기서는 두 날짜의 논리적 순서만 추가 검증한다.
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            // GlobalExceptionHandler(shared-common)가 IllegalArgumentException → 400 으로 매핑한다.
            // ResponseStatusException 은 그 핸들러 catch-all(Exception→500)에 먼저 걸려 500 이 되므로 쓰지 않는다.
            throw new IllegalArgumentException(
                    "startDate 는 endDate 보다 늦을 수 없습니다: " + startDate + " > " + endDate);
        }
        SettlementPageResponse response = searchRepository.search(
                ordererName, productName, isRefunded,
                status, startDate, endDate,
                page, size, sortBy, sortDirection);
        return ResponseEntity.ok(response);
    }

    /**
     * 날짜 파라미터 형식 오류(비-ISO)는 바인딩 단계에서 MethodArgumentTypeMismatchException 으로
     * 던져진다. shared-common GlobalExceptionHandler 에는 전용 매핑이 없어 catch-all(500)로
     * 새므로, 이 컨트롤러 로컬 핸들러로 400 을 명시한다(로컬 핸들러가 advice 보다 우선).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Void> handleDateTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }
}
