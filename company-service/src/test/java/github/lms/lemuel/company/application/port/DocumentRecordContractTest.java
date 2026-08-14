package github.lms.lemuel.company.application.port;

import github.lms.lemuel.company.application.port.in.GetCompanyDocumentsUseCase.DocumentDownload;
import github.lms.lemuel.company.application.port.in.UploadCompanyDocumentUseCase.UploadCommand;
import github.lms.lemuel.company.application.port.out.LoadCompanyDocumentPort.DocumentContent;
import github.lms.lemuel.company.domain.CompanyDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * byte[] 를 품은 포트 record 3종의 값 계약.
 *
 * <p>record 기본 구현은 배열을 <b>참조 동일성</b>으로 비교하므로, 내용이 같아도 다른 객체가 된다.
 * 문서함은 같은 파일을 재업로드하면 교체하는 의미론이라 내용 기준 비교가 맞다.
 * toString 은 파일 바이트를 로그로 흘리지 않아야 한다(기업 기밀 + 로그 오염).
 */
@DisplayName("문서 포트 record — 배열 값 계약")
class DocumentRecordContractTest {

    /**
     * equals 의 타입 가드 분기를 덮기 위한 이종 객체.
     *
     * <p>단정문에 문자열 리터럴을 직접 넣으면 "다른 타입끼리 비교하는 단정"으로 잡힌다(S5845).
     * 그 규칙이 노리는 것은 <i>실수로</i> 다른 타입을 비교하는 테스트인데, 여기서는 그게 검증 대상이다.
     * {@code Object} 로 받아 의도를 코드에 드러낸다 — 규칙 회피가 아니라 "일부러 이종을 넣는다"는 선언이다.
     */
    private static final Object FOREIGN_TYPE = "다른 타입";

    private static CompanyDocument doc() {
        return CompanyDocument.create("005930", "제목", "brief.docx", 12L, java.time.Instant.parse("2026-08-13T00:00:00Z"));
    }

    @Test
    @DisplayName("UploadCommand: 내용이 같으면 같은 값이다 — 배열 참조가 달라도")
    void uploadCommand_equalsByContent() {
        UploadCommand a = new UploadCommand("005930", "제목", "brief.docx", new byte[]{1, 2, 3});
        UploadCommand b = new UploadCommand("005930", "제목", "brief.docx", new byte[]{1, 2, 3});

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    @DisplayName("UploadCommand: 내용이 다르면 다른 값이다")
    void uploadCommand_differsByContent() {
        UploadCommand a = new UploadCommand("005930", "제목", "brief.docx", new byte[]{1, 2, 3});
        UploadCommand b = new UploadCommand("005930", "제목", "brief.docx", new byte[]{9});

        assertThat(a).isNotEqualTo(b).isNotEqualTo(null).isNotEqualTo(FOREIGN_TYPE);
    }

    @Test
    @DisplayName("UploadCommand: toString 은 바이트가 아니라 길이만 노출한다")
    void uploadCommand_toStringHidesBytes() {
        String s = new UploadCommand("005930", "제목", "brief.docx", new byte[]{1, 2, 3}).toString();

        assertThat(s).contains("3B").contains("brief.docx").doesNotContain("[B@");
        assertThat(new UploadCommand("005930", "제목", "f", null).toString()).contains("content=null");
    }

    @Test
    @DisplayName("DocumentDownload: 내용 기준 비교 + toString 이 바이트를 감춘다")
    void documentDownload_contract() {
        CompanyDocument d = doc();
        DocumentDownload a = new DocumentDownload(d, new byte[]{7, 7});
        DocumentDownload b = new DocumentDownload(d, new byte[]{7, 7});

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(new DocumentDownload(d, new byte[]{7})).isNotEqualTo(FOREIGN_TYPE);
        assertThat(a.toString()).contains("2B").doesNotContain("[B@");
        assertThat(new DocumentDownload(d, null).toString()).contains("content=null");
    }

    @Test
    @DisplayName("DocumentContent: 내용 기준 비교 + toString 이 바이트를 감춘다")
    void documentContent_contract() {
        CompanyDocument d = doc();
        DocumentContent a = new DocumentContent(d, new byte[]{5});
        DocumentContent b = new DocumentContent(d, new byte[]{5});

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(new DocumentContent(d, new byte[]{6})).isNotEqualTo(FOREIGN_TYPE);
        assertThat(a.toString()).contains("1B").doesNotContain("[B@");
        assertThat(new DocumentContent(d, null).toString()).contains("content=null");
    }
}
