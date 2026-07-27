package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.application.port.in.ImportCompanyWorkforceUseCase;
import github.lms.lemuel.company.application.port.in.ImportCompanyWorkforceUseCase.ImportResult;
import github.lms.lemuel.company.audit.application.port.out.RecordAuditPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Map;

/**
 * 국민연금 사업장가입자 CSV 1회 적재 (운영자 전용 — AdminApiKeyFilter 게이팅, gateway 미라우팅).
 *
 * <p>파일 경로를 요청 바디로 받는다 — 원본이 개발자 로컬 PC마다 다른 경로에 있어 설정값 고정보다
 * 유연하다. 단건 배치 INSERT 라 동기 처리로 충분(뉴스 수집 트리거의 비동기+상태추적 패턴은
 * "기업 수 × 외부 API 호출 간격" 때문에 필요했던 것이라 여기엔 과설계).
 */
@RestController
@RequestMapping("/admin/company/workforce")
public class CompanyWorkforceImportAdminController {

    private final ImportCompanyWorkforceUseCase importCompanyWorkforceUseCase;
    private final RecordAuditPort recordAuditPort;

    public CompanyWorkforceImportAdminController(ImportCompanyWorkforceUseCase importCompanyWorkforceUseCase,
                                                 RecordAuditPort recordAuditPort) {
        this.importCompanyWorkforceUseCase = importCompanyWorkforceUseCase;
        this.recordAuditPort = recordAuditPort;
    }

    @PostMapping("/import")
    public ResponseEntity<ImportResult> importCsv(@RequestBody ImportRequest request) {
        Path csvPath = Path.of(request.path());
        ImportResult result = importCompanyWorkforceUseCase.importFrom(csvPath);
        // resourceId 는 audit_logs.resource_id VARCHAR(64) — 절대경로가 아니라 파일명만(전체 경로는
        // detail 에 넣는다). 절대경로를 그대로 넘기면 flush 시점 truncation 오류로 REQUIRES_NEW
        // 감사 트랜잭션이 rollback-only 가 되어 UnexpectedRollbackException 으로 본 요청까지 500 남(재현 확인).
        recordAuditPort.record("WORKFORCE_IMPORTED", "CompanyWorkforce", csvPath.getFileName().toString(),
                Map.of("path", request.path(), "received", result.received(),
                        "imported", result.imported(), "skipped", result.skipped()));
        return ResponseEntity.ok(result);
    }

    public record ImportRequest(String path) {
    }
}
