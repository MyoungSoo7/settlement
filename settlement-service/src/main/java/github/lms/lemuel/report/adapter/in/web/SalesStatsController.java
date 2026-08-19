package github.lms.lemuel.report.adapter.in.web;

import github.lms.lemuel.report.adapter.in.web.dto.SalesBreakdownResponse;
import github.lms.lemuel.report.adapter.in.web.dto.SalesSummaryResponse;
import github.lms.lemuel.report.application.port.in.QuerySalesStatsUseCase;
import github.lms.lemuel.report.domain.ReportPeriod;
import github.lms.lemuel.report.domain.SalesDimension;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 매출 통계 API — 운영 대시보드(/admin/settlement/sales-stats)가 읽는다.
 *
 * <pre>
 *   GET /api/reports/sales-stats/summary?from=&to=
 *   GET /api/reports/sales-stats/breakdown?from=&to=&dimension=&limit=
 * </pre>
 *
 * <p>기간별 추이는 여기서 다시 만들지 않는다 — 이미 {@code GET /api/reports/cashflow} 가
 * 일·주·월 버킷으로 답한다. 같은 집계를 두 벌 두면 두 화면이 서로 다른 숫자를 말하게 된다.
 *
 * <p>보안: {@code /api/reports/**} 는 SecurityConfig 에서 ADMIN/MANAGER 로 제한된다
 * (별도 매처 추가 불필요 — 기존 리포트 API 와 같은 등급).
 *
 * <p>잘못된 기간(역전·366일 초과)은 {@code InvalidReportPeriodException} → 400,
 * 잘못된 {@code dimension} 은 Spring 의 enum 변환 실패 → 400 으로 각각 매핑된다.
 */
@Tag(name = "Sales Stats", description = "매출 통계 — 기간 요약·전기 대비·축별 구성비")
@RestController
@RequestMapping("/api/reports/sales-stats")
@RequiredArgsConstructor
public class SalesStatsController {

    private final QuerySalesStatsUseCase querySalesStatsUseCase;

    @Operation(summary = "기간 매출 요약",
            description = "기간 합계와 직전 동일 길이 기간 대비 증감률. 직전 기간이 0 이면 증감률은 null 이다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "기간 역전·366일 초과·날짜 형식 오류")
    })
    @GetMapping("/summary")
    public ResponseEntity<SalesSummaryResponse> summary(
            @Parameter(description = "시작일 (yyyy-MM-dd)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "종료일 (yyyy-MM-dd, 포함)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return ResponseEntity.ok(SalesSummaryResponse.from(
                querySalesStatsUseCase.summary(ReportPeriod.of(from, to))));
    }

    @Operation(summary = "축별 매출 구성",
            description = "결제수단·셀러등급·정산상태·셀러·상품 중 한 축으로 매출을 가른다. 거래액 큰 순 상위 limit 개.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "기간 오류 또는 지원하지 않는 dimension")
    })
    @GetMapping("/breakdown")
    public ResponseEntity<SalesBreakdownResponse> breakdown(
            @Parameter(description = "시작일 (yyyy-MM-dd)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "종료일 (yyyy-MM-dd, 포함)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "집계 축")
            @RequestParam SalesDimension dimension,
            @Parameter(description = "상위 N (1~100, 범위를 벗어나면 서버가 클램프한다)")
            @RequestParam(defaultValue = "10") int limit) {

        return ResponseEntity.ok(SalesBreakdownResponse.from(dimension,
                querySalesStatsUseCase.breakdown(ReportPeriod.of(from, to), dimension, limit)));
    }
}
