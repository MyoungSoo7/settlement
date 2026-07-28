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

    /** audit_logs.resource_id VARCHAR(64) — 넘기면 flush 시점 truncation 으로 요청 전체가 500 난다. */
    private static final int AUDIT_RESOURCE_ID_MAX = 64;

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
        // resourceId 는 파일명만 — 전체 경로는 detail 에 넣는다(사유는 auditResourceId 참조).
        recordAuditPort.record("WORKFORCE_IMPORTED", "CompanyWorkforce", auditResourceId(request.path()),
                Map.of("path", request.path(), "received", result.received(),
                        "imported", result.imported(), "skipped", result.skipped()));
        return ResponseEntity.ok(result);
    }

    /**
     * 감사용 식별자 = 파일명, 최대 64자.
     *
     * <p>{@link Path#getFileName()} 은 기본 FileSystem 의 구분자만 인식한다 — 리눅스 런타임에
     * 윈도우 절대경로가 들어오면 백슬래시가 구분자가 아니라서 경로 전체가 "파일명"으로 나온다.
     * 그래서 원문 문자열에서 두 구분자 모두로 마지막 세그먼트를 뽑고, 컬럼 길이로 잘라
     * 어떤 입력이 와도 제약을 넘지 않게 한다.
     */
    private static String auditResourceId(String rawPath) {
        int cut = Math.max(rawPath.lastIndexOf('/'), rawPath.lastIndexOf('\\'));
        String fileName = rawPath.substring(cut + 1);
        return fileName.length() <= AUDIT_RESOURCE_ID_MAX ? fileName : fileName.substring(0, AUDIT_RESOURCE_ID_MAX);
    }

    public record ImportRequest(String path) {
    }
}
