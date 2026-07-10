package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.adapter.in.web.dto.CompanyDocumentResponse;
import github.lms.lemuel.company.application.port.in.GetCompanyDocumentsUseCase;
import github.lms.lemuel.company.domain.CompanyDocument;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 기업 문서 공개 조회 API — 목록(메타데이터)과 다운로드.
 * 업로드는 {@link CompanyDocumentAdminController} (AdminApiKeyFilter 게이팅) 전용.
 */
@RestController
@RequestMapping("/api/company")
public class CompanyDocumentController {

    private final GetCompanyDocumentsUseCase getCompanyDocumentsUseCase;

    public CompanyDocumentController(GetCompanyDocumentsUseCase getCompanyDocumentsUseCase) {
        this.getCompanyDocumentsUseCase = getCompanyDocumentsUseCase;
    }

    @GetMapping("/companies/{stockCode}/documents")
    public ResponseEntity<List<CompanyDocumentResponse>> byCompany(@PathVariable String stockCode) {
        return ResponseEntity.ok(
                getCompanyDocumentsUseCase.byCompany(stockCode).stream()
                        .map(CompanyDocumentResponse::from)
                        .toList());
    }

    @GetMapping("/documents/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        GetCompanyDocumentsUseCase.DocumentDownload download = getCompanyDocumentsUseCase.download(id)
                .orElseThrow(() -> new NoSuchElementException("문서를 찾을 수 없습니다: " + id));
        CompanyDocument document = download.document();
        // 한글 파일명 — RFC 5987 filename* 인코딩 (ContentDisposition 이 처리)
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(document.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(download.content());
    }
}
