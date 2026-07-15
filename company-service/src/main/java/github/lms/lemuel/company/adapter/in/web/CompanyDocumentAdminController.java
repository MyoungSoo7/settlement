package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.adapter.in.web.dto.CompanyDocumentResponse;
import github.lms.lemuel.company.application.port.in.UploadCompanyDocumentUseCase;
import github.lms.lemuel.company.application.port.in.UploadCompanyDocumentUseCase.UploadCommand;
import github.lms.lemuel.company.audit.application.port.out.RecordAuditPort;
import github.lms.lemuel.company.domain.CompanyDocument;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * 기업 문서 업로드 (운영자 전용 — AdminApiKeyFilter 게이팅, gateway 미라우팅).
 *
 * <p>외부 파이프라인(예: trusted-ceo-agent 브리핑)이 만든 docx·pdf·png·md 산출물을
 * 기업 문서함에 적재한다. 같은 (stockCode, fileName) 은 교체 — 재생성 브리핑이 최신본이 된다.
 * 검증(확장자·크기·파일명)은 도메인이 소유하고, Content-Type 은 확장자에서 서버가 파생한다.
 */
@RestController
@RequestMapping("/admin/company/documents")
public class CompanyDocumentAdminController {

    private final UploadCompanyDocumentUseCase uploadCompanyDocumentUseCase;
    private final RecordAuditPort recordAuditPort;

    public CompanyDocumentAdminController(UploadCompanyDocumentUseCase uploadCompanyDocumentUseCase,
                                          RecordAuditPort recordAuditPort) {
        this.uploadCompanyDocumentUseCase = uploadCompanyDocumentUseCase;
        this.recordAuditPort = recordAuditPort;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CompanyDocumentResponse> upload(
            @RequestParam("stockCode") String stockCode,
            @RequestParam(value = "title", required = false) String title,
            @RequestPart("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 파일을 읽지 못했습니다", e);
        }
        CompanyDocument saved = uploadCompanyDocumentUseCase.upload(
                new UploadCommand(stockCode, title, file.getOriginalFilename(), content));
        recordAuditPort.record("DOCUMENT_UPLOADED", "CompanyDocument", stockCode,
                Map.of("fileName", String.valueOf(file.getOriginalFilename()), "sizeBytes", content.length));
        return ResponseEntity.status(HttpStatus.CREATED).body(CompanyDocumentResponse.from(saved));
    }
}
