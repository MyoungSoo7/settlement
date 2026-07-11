package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.in.GetCompanyDocumentsUseCase;
import github.lms.lemuel.company.application.port.in.UploadCompanyDocumentUseCase;
import github.lms.lemuel.company.application.port.out.LoadCompanyDocumentPort;
import github.lms.lemuel.company.application.port.out.LoadCompanyPort;
import github.lms.lemuel.company.application.port.out.SaveCompanyDocumentPort;
import github.lms.lemuel.company.domain.CompanyDocument;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * 기업 문서함 — 외부 파이프라인 산출물(CEO 브리핑 docx 등)의 업로드·조회.
 *
 * <p>업로드는 등록된 기업(stockCode)에만 허용하고, 검증(확장자 허용 목록·크기 상한·파일명)은
 * 도메인 {@link CompanyDocument#create} 가 소유한다. 같은 (stockCode, fileName) 재업로드는
 * 교체 — 재생성된 브리핑이 자연스럽게 최신본이 된다.
 */
@Service
public class CompanyDocumentService implements UploadCompanyDocumentUseCase, GetCompanyDocumentsUseCase {

    private final LoadCompanyPort loadCompanyPort;
    private final LoadCompanyDocumentPort loadCompanyDocumentPort;
    private final SaveCompanyDocumentPort saveCompanyDocumentPort;

    public CompanyDocumentService(LoadCompanyPort loadCompanyPort,
                                  LoadCompanyDocumentPort loadCompanyDocumentPort,
                                  SaveCompanyDocumentPort saveCompanyDocumentPort) {
        this.loadCompanyPort = loadCompanyPort;
        this.loadCompanyDocumentPort = loadCompanyDocumentPort;
        this.saveCompanyDocumentPort = saveCompanyDocumentPort;
    }

    @Override
    public CompanyDocument upload(UploadCommand command) {
        if (command == null || command.content() == null || command.content().length == 0) {
            throw new IllegalArgumentException("파일 내용이 비어 있습니다");
        }
        loadCompanyPort.findByStockCode(command.stockCode())
                .orElseThrow(() -> new NoSuchElementException("기업을 찾을 수 없습니다: " + command.stockCode()));
        CompanyDocument document = CompanyDocument.create(command.stockCode(), command.title(),
                command.fileName(), command.content().length, Instant.now());
        return saveCompanyDocumentPort.saveOrReplace(document, command.content());
    }

    @Override
    public List<CompanyDocument> byCompany(String stockCode) {
        loadCompanyPort.findByStockCode(stockCode)
                .orElseThrow(() -> new NoSuchElementException("기업을 찾을 수 없습니다: " + stockCode));
        return loadCompanyDocumentPort.findByStockCode(stockCode);
    }

    @Override
    public Optional<DocumentDownload> download(Long id) {
        return loadCompanyDocumentPort.findWithContent(id)
                .map(found -> new DocumentDownload(found.document(), found.content()));
    }
}
