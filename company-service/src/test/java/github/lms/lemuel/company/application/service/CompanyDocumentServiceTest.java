package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.in.GetCompanyDocumentsUseCase.DocumentDownload;
import github.lms.lemuel.company.application.port.in.UploadCompanyDocumentUseCase.UploadCommand;
import github.lms.lemuel.company.application.port.out.LoadCompanyDocumentPort;
import github.lms.lemuel.company.application.port.out.LoadCompanyPort;
import github.lms.lemuel.company.application.port.out.SaveCompanyDocumentPort;
import github.lms.lemuel.company.domain.Company;
import github.lms.lemuel.company.domain.CompanyDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyDocumentServiceTest {

    @Mock
    private LoadCompanyPort loadCompanyPort;
    @Mock
    private LoadCompanyDocumentPort loadCompanyDocumentPort;
    @Mock
    private SaveCompanyDocumentPort saveCompanyDocumentPort;
    @InjectMocks
    private CompanyDocumentService service;

    private static final String STOCK = "005930";
    private static final byte[] CONTENT = "docx-bytes".getBytes(StandardCharsets.UTF_8);

    private static CompanyDocument saved() {
        return CompanyDocument.rehydrate(1L, STOCK, "브리핑", "briefing.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                CONTENT.length, Instant.parse("2026-07-11T00:00:00Z"));
    }

    private void companyExists() {
        when(loadCompanyPort.findByStockCode(STOCK))
                .thenReturn(Optional.of(new Company(STOCK, null, "삼성전자", "KOSPI")));
    }

    @Test
    @DisplayName("upload — 기업이 존재하면 도메인 검증을 거쳐 저장 포트에 위임한다")
    void uploadHappyPath() {
        companyExists();
        when(saveCompanyDocumentPort.saveOrReplace(any(), any())).thenReturn(saved());

        CompanyDocument result = service.upload(new UploadCommand(STOCK, "브리핑", "briefing.docx", CONTENT));

        assertEquals(1L, result.id());
        ArgumentCaptor<CompanyDocument> captor = ArgumentCaptor.forClass(CompanyDocument.class);
        verify(saveCompanyDocumentPort).saveOrReplace(captor.capture(), any());
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                captor.getValue().contentType());
        assertEquals(CONTENT.length, captor.getValue().sizeBytes());
    }

    @Test
    @DisplayName("upload — 등록되지 않은 기업이면 NoSuchElementException")
    void uploadThrowsWhenCompanyMissing() {
        when(loadCompanyPort.findByStockCode(STOCK)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.upload(new UploadCommand(STOCK, null, "briefing.docx", CONTENT)));
        verifyNoInteractions(saveCompanyDocumentPort);
    }

    @Test
    @DisplayName("upload — 내용이 비면(null·0바이트) 거부한다")
    void uploadRejectsEmptyContent() {
        assertThrows(IllegalArgumentException.class,
                () -> service.upload(new UploadCommand(STOCK, null, "briefing.docx", null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.upload(new UploadCommand(STOCK, null, "briefing.docx", new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> service.upload(null));
        verifyNoInteractions(saveCompanyDocumentPort);
    }

    @Test
    @DisplayName("upload — 도메인 검증 실패(확장자)는 저장 전에 막힌다")
    void uploadRejectsDisallowedExtension() {
        companyExists();

        assertThrows(IllegalArgumentException.class,
                () -> service.upload(new UploadCommand(STOCK, null, "malware.exe", CONTENT)));
        verifyNoInteractions(saveCompanyDocumentPort);
    }

    @Test
    @DisplayName("byCompany — 기업이 존재하면 메타데이터 목록을 반환한다")
    void byCompanyReturnsList() {
        companyExists();
        when(loadCompanyDocumentPort.findByStockCode(STOCK)).thenReturn(List.of(saved()));

        List<CompanyDocument> result = service.byCompany(STOCK);

        assertEquals(1, result.size());
        assertEquals("briefing.docx", result.get(0).fileName());
    }

    @Test
    @DisplayName("byCompany — 존재하지 않는 기업이면 NoSuchElementException")
    void byCompanyThrowsWhenCompanyMissing() {
        when(loadCompanyPort.findByStockCode(STOCK)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.byCompany(STOCK));
    }

    @Test
    @DisplayName("download — 메타데이터와 파일 바이트를 함께 반환한다")
    void downloadReturnsContent() {
        when(loadCompanyDocumentPort.findWithContent(1L))
                .thenReturn(Optional.of(new LoadCompanyDocumentPort.DocumentContent(saved(), CONTENT)));

        Optional<DocumentDownload> result = service.download(1L);

        assertTrue(result.isPresent());
        assertEquals("briefing.docx", result.get().document().fileName());
        assertArrayEquals(CONTENT, result.get().content());
    }

    @Test
    @DisplayName("download — 없는 문서면 빈 Optional")
    void downloadEmptyWhenMissing() {
        when(loadCompanyDocumentPort.findWithContent(99L)).thenReturn(Optional.empty());

        assertTrue(service.download(99L).isEmpty());
    }
}
