package github.lms.lemuel.card.adapter.in.web;

import github.lms.lemuel.card.adapter.in.web.ExpenseReceiptAdapter.ExpenseReceiptResponse;
import github.lms.lemuel.card.adapter.in.web.ExpenseReceiptAdapter.ReviewRequest;
import github.lms.lemuel.card.application.port.in.AttachExpenseReceiptUseCase;
import github.lms.lemuel.card.application.port.in.AttachExpenseReceiptUseCase.AttachReceiptCommand;
import github.lms.lemuel.card.application.port.in.GetExpenseReceiptUseCase;
import github.lms.lemuel.card.application.port.in.ReviewExpenseReceiptUseCase;
import github.lms.lemuel.card.application.port.in.ReviewExpenseReceiptUseCase.ReviewReceiptCommand;
import github.lms.lemuel.card.domain.ExpenseReceipt;
import github.lms.lemuel.card.domain.ExtractedReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 영수증 REST 어댑터 (ADR 0036).
 *
 * <p>어댑터가 하는 일은 "요청 → 커맨드 → 응답 DTO" 변환뿐이고, 그 변환에서 금액이 지수표기로
 * 새거나(DATA-STANDARD N5) 파일 본문이 응답에 실리면 곤란하다. 그 두 가지와 멀티파트 메타
 * 전달을 고정한다.
 */
class ExpenseReceiptAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-14T01:00:00Z");
    private static final byte[] CONTENT = "jpg-bytes".getBytes(StandardCharsets.UTF_8);

    private AttachExpenseReceiptUseCase attachUseCase;
    private GetExpenseReceiptUseCase getUseCase;
    private ReviewExpenseReceiptUseCase reviewUseCase;
    private ExpenseReceiptAdapter adapter;

    @BeforeEach
    void setUp() {
        attachUseCase = mock(AttachExpenseReceiptUseCase.class);
        getUseCase = mock(GetExpenseReceiptUseCase.class);
        reviewUseCase = mock(ReviewExpenseReceiptUseCase.class);
        adapter = new ExpenseReceiptAdapter(attachUseCase, getUseCase, reviewUseCase);
    }

    private static ExpenseReceipt receipt() {
        return ExpenseReceipt.extracted("RPT-1", "CAP-1", 3L, 77L, "영수증.jpg", "image/jpeg",
                "hash-abc", 2048L,
                new ExtractedReceipt("스타벅스 강남점", LocalDate.of(2026, 8, 12),
                        new BigDecimal("12000"), new BigDecimal("0.91"), new BigDecimal("0.91")),
                "gemini-2.5-flash", NOW);
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "영수증.jpg", "image/jpeg", CONTENT);
    }

    @Test
    @DisplayName("업로드는 보고서·업로더·파일 메타를 그대로 커맨드로 옮긴다")
    void attachMapsCommand() {
        when(attachUseCase.attach(any(AttachReceiptCommand.class))).thenReturn(receipt());

        var response = adapter.attach("RPT-1", file(), 77L);

        ArgumentCaptor<AttachReceiptCommand> captor = ArgumentCaptor.forClass(AttachReceiptCommand.class);
        verify(attachUseCase).attach(captor.capture());
        assertThat(captor.getValue().reportId()).isEqualTo("RPT-1");
        assertThat(captor.getValue().uploaderUserId()).isEqualTo(77L);
        assertThat(captor.getValue().fileName()).isEqualTo("영수증.jpg");
        assertThat(captor.getValue().contentType()).isEqualTo("image/jpeg");
        assertThat(captor.getValue().content()).isEqualTo(CONTENT);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("응답은 금액·신뢰도를 십진 문자열로 주고 파일 본문은 싣지 않는다")
    void responseCarriesPlainStrings() {
        when(attachUseCase.attach(any(AttachReceiptCommand.class))).thenReturn(receipt());

        ExpenseReceiptResponse body = adapter.attach("RPT-1", file(), 77L).getBody();

        assertThat(body).isNotNull();
        assertThat(body.totalAmount()).isEqualTo("12000");
        assertThat(body.confidence()).isEqualTo("0.91");
        assertThat(body.merchantName()).isEqualTo("스타벅스 강남점");
        assertThat(body.transactionDate()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(body.reportId()).isEqualTo("RPT-1");
        assertThat(body.captureId()).isEqualTo("CAP-1");
        assertThat(body.fileName()).isEqualTo("영수증.jpg");
    }

    @Test
    @DisplayName("최신 영수증이 있으면 200, 없으면 404")
    void latestReturnsOkOrNotFound() {
        when(getUseCase.latestForReport("RPT-1")).thenReturn(Optional.of(receipt()));
        assertThat(adapter.latest("RPT-1").getStatusCode()).isEqualTo(HttpStatus.OK);

        when(getUseCase.latestForReport("RPT-1")).thenReturn(Optional.empty());
        assertThat(adapter.latest("RPT-1").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("리뷰 종결은 리뷰어·판정·사유를 그대로 위임한다")
    void reviewMapsCommand() {
        when(reviewUseCase.review(any(ReviewReceiptCommand.class))).thenReturn(receipt());

        adapter.review(5L, new ReviewRequest(99L, false, "가맹점 불일치"));

        ArgumentCaptor<ReviewReceiptCommand> captor = ArgumentCaptor.forClass(ReviewReceiptCommand.class);
        verify(reviewUseCase).review(captor.capture());
        assertThat(captor.getValue().receiptId()).isEqualTo(5L);
        assertThat(captor.getValue().reviewerId()).isEqualTo(99L);
        assertThat(captor.getValue().matched()).isFalse();
        assertThat(captor.getValue().note()).isEqualTo("가맹점 불일치");
    }
}
