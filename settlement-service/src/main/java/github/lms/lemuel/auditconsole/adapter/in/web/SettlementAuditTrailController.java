package github.lms.lemuel.auditconsole.adapter.in.web;

import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditActionCount;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogExport;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogPage;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogQuery;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogRow;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.common.web.csv.CsvResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 정산 감사 이력 콘솔 — 자금을 움직인 조작의 기록.
 *
 * <pre>
 *   GET /admin/audit-trail                → 조건 검색(최신순 페이지)
 *   GET /admin/audit-trail/action-counts  → 같은 조건의 액션별 건수
 *   GET /admin/audit-trail/actions        → 필터 드롭다운용 액션 목록
 *   GET /admin/audit-trail/export         → 같은 조건의 CSV 내려받기
 * </pre>
 *
 * <p><b>왜 order 와 경로가 다른가</b>: 감사 테이블은 서비스마다 자기 DB 에 따로 있다
 * (order 는 opslab 스키마의 {@code audit_logs}, settlement 은 {@code settlement_db public.audit_logs}).
 * MSA 경계상 한쪽이 다른 쪽 테이블을 읽을 수 없으므로 표면도 둘이다. 같은 경로를 쓰면
 * 게이트웨이가 한쪽으로만 보내 나머지 하나가 <b>존재하는데 도달 불가</b>한 상태가 된다.
 *
 * <p>여기 남는 것은 지급 실행·차지백 판정·PG 대사 마감·역분개 백필처럼 <b>돈이 실제로 움직이거나
 * 장부가 바뀐</b> 조작이다. 조회 자체는 상태를 바꾸지 않지만 권한은 ADMIN 으로 좁힌다 —
 * 조작자와 열람자가 같으면 감사가 성립하지 않는다.
 *
 * <p>조회 로직은 shared-common {@code common.audit} 한 벌을 공유한다. Hibernate 의
 * {@code default_schema} 가 각 서비스의 테이블을 가리키므로 같은 JPQL 이 양쪽에서 자기 것을 읽는다.
 */
@Tag(name = "Settlement Audit Trail", description = "정산 감사 이력 조회")
@RestController
@RequestMapping("/admin/audit-trail")
public class SettlementAuditTrailController {

    private final SearchAuditLogsUseCase searchAuditLogsUseCase;

    public SettlementAuditTrailController(SearchAuditLogsUseCase searchAuditLogsUseCase) {
        this.searchAuditLogsUseCase = searchAuditLogsUseCase;
    }

    @GetMapping
    @Operation(summary = "정산 감사 이력 검색", description = "기간·행위자·액션·리소스로 좁혀 최신순으로 조회한다")
    public ResponseEntity<AuditLogPage> search(
            @RequestParam(required = false) String actorEmail,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        return ResponseEntity.ok(searchAuditLogsUseCase.search(
                toQuery(actorEmail, actorId, action, resourceType, resourceId, from, to, page, size)));
    }

    @GetMapping("/action-counts")
    @Operation(summary = "액션별 건수", description = "목록을 넘기기 전에 '무슨 일이 얼마나 있었나'를 먼저 보여준다")
    public ResponseEntity<List<AuditActionCount>> actionCounts(
            @RequestParam(required = false) String actorEmail,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return ResponseEntity.ok(searchAuditLogsUseCase.countByAction(
                toQuery(actorEmail, actorId, action, resourceType, resourceId, from, to, 0, 1)));
    }

    @GetMapping("/actions")
    @Operation(summary = "감사 액션 목록", description = "필터 드롭다운용 — 서버 enum 이 정본이다")
    public ResponseEntity<List<String>> actions() {
        return ResponseEntity.ok(Arrays.stream(AuditAction.values()).map(Enum::name).sorted().toList());
    }

    @GetMapping("/export")
    @Operation(summary = "정산 감사 이력 CSV", description = "화면과 같은 조건으로 최대 5000행을 내려받는다")
    public ResponseEntity<ByteArrayResource> export(
            @RequestParam(required = false) String actorEmail,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        AuditLogExport export = searchAuditLogsUseCase.export(
                toQuery(actorEmail, actorId, action, resourceType, resourceId, from, to, 0, 1));

        ResponseEntity<ByteArrayResource> csv = CsvResponse.of(
                "settlement-audit-trail",
                List.of("일시", "행위자ID", "행위자", "액션", "리소스유형", "리소스ID", "IP", "상세"),
                export.rows(),
                SettlementAuditTrailController::toCells);

        return ResponseEntity.status(csv.getStatusCode())
                .headers(csv.getHeaders())
                .header("X-Export-Truncated", String.valueOf(export.truncated()))
                .header("X-Export-Total", String.valueOf(export.totalElements()))
                .body(csv.getBody());
    }

    private static List<String> toCells(AuditLogRow row) {
        return List.of(
                Objects.toString(row.createdAt(), ""),
                Objects.toString(row.actorId(), ""),
                Objects.toString(row.actorEmail(), ""),
                Objects.toString(row.action(), ""),
                Objects.toString(row.resourceType(), ""),
                Objects.toString(row.resourceId(), ""),
                Objects.toString(row.ipAddress(), ""),
                Objects.toString(row.detailJson(), ""));
    }

    /** 모르는 액션 이름은 필터 미적용으로 흘린다 — 필터 하나 때문에 이력 전체가 막히면 안 된다. */
    private static AuditLogQuery toQuery(String actorEmail, Long actorId, String action,
                                         String resourceType, String resourceId,
                                         LocalDate from, LocalDate to, int page, int size) {
        AuditAction parsed = null;
        if (action != null && !action.isBlank()) {
            try {
                parsed = AuditAction.valueOf(action.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                parsed = null;
            }
        }
        return new AuditLogQuery(actorEmail, actorId, parsed, resourceType, resourceId,
                from, to, page, size);
    }
}
