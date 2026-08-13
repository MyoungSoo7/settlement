package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.application.port.out.LoadCollateralDocumentPort;
import github.lms.lemuel.loan.application.port.out.LoadSecuredLoanPort;
import github.lms.lemuel.loan.application.port.out.LoanMetricsPort;
import github.lms.lemuel.loan.application.port.out.PublishSecuredLoanEventPort;
import github.lms.lemuel.loan.application.port.out.SaveSecuredLoanPort;
import github.lms.lemuel.loan.domain.Borrower;
import github.lms.lemuel.loan.domain.BorrowerType;
import github.lms.lemuel.loan.domain.Collateral;
import github.lms.lemuel.loan.domain.CollateralDocument;
import github.lms.lemuel.loan.domain.CollateralDocumentMatchDecision;
import github.lms.lemuel.loan.domain.CollateralDocumentStatus;
import github.lms.lemuel.loan.domain.CollateralStatus;
import github.lms.lemuel.loan.domain.CollateralType;
import github.lms.lemuel.loan.domain.ExtractedCollateralDocument;
import github.lms.lemuel.loan.domain.LoanProductType;
import github.lms.lemuel.loan.domain.RepaymentMethod;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.SecuredLoanStatus;
import github.lms.lemuel.loan.domain.exception.CollateralDocumentNotMatchedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 담보서류 대사 게이트(ADR 0036) — 서류가 첨부돼 있으면 최신 서류가 MATCHED 여야 승인이 통과한다.
 *
 * <p>고정하는 것: ① 대사 미통과(MISMATCHED/NEEDS_REVIEW)는 422 로 거절되고 대출·담보 상태가
 * 움직이지 않는다 ② 서류가 없으면 기존 경로 그대로 통과(점진 도입) ③ 게이트는 상태 전이보다 먼저다.
 */
@ExtendWith(MockitoExtension.class)
class DisburseSecuredLoanDocumentGateTest {

    private static final LocalDateTime APPRAISED_AT = LocalDateTime.of(2026, 8, 10, 14, 0);
    private static final Clock FIXED = Clock.fixed(
            Instant.parse("2026-08-14T01:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock LoadSecuredLoanPort loadSecuredLoanPort;
    @Mock SaveSecuredLoanPort saveSecuredLoanPort;
    @Mock AppendLedgerPort appendLedgerPort;
    @Mock PublishSecuredLoanEventPort publishSecuredLoanEventPort;
    @Mock LoanMetricsPort loanMetricsPort;
    @Mock LoadCollateralDocumentPort loadCollateralDocumentPort;

    private DisburseSecuredLoanService service;

    @BeforeEach
    void setUp() {
        service = new DisburseSecuredLoanService(loadSecuredLoanPort, saveSecuredLoanPort,
                appendLedgerPort, publishSecuredLoanEventPort, loanMetricsPort,
                loadCollateralDocumentPort, FIXED);
    }

    private static SecuredLoan requestedLoan() {
        Collateral collateral = Collateral.reconstitute(2L, CollateralType.REAL_ESTATE,
                "서울시 강남구 역삼동 123-4", new BigDecimal("500000000.00"),
                new BigDecimal("120000000.00"), APPRAISED_AT, CollateralStatus.PLEDGED);
        return SecuredLoan.reconstitute(1L, new Borrower(BorrowerType.INDIVIDUAL, 77L, "홍길동", null),
                LoanProductType.MORTGAGE, collateral, new BigDecimal("200000000.00"), 120,
                new BigDecimal("4.3"), RepaymentMethod.EQUAL_PAYMENT, null, null,
                BigDecimal.ZERO, SecuredLoanStatus.REQUESTED, APPRAISED_AT, null);
    }

    private static CollateralDocument documentIn(CollateralDocumentStatus status) {
        CollateralDocument document = CollateralDocument.extracted(1L, 2L, 77L, "감정평가서.pdf",
                "application/pdf", "hash", 4096L,
                new ExtractedCollateralDocument("홍길동", "서울시 강남구",
                        new BigDecimal("500000000"), new BigDecimal("120000000"),
                        LocalDate.of(2026, 8, 10), new BigDecimal("0.93")),
                "gemini-2.5-flash", APPRAISED_AT);
        switch (status) {
            case MATCHED -> document.applyDecision(CollateralDocumentMatchDecision.matched(), APPRAISED_AT);
            case MISMATCHED -> document.applyDecision(
                    CollateralDocumentMatchDecision.mismatched("감정평가액 불일치"), APPRAISED_AT);
            case NEEDS_REVIEW -> document.applyDecision(
                    CollateralDocumentMatchDecision.needsReview("신뢰도 미달"), APPRAISED_AT);
            case EXTRACTED -> { }
        }
        return document;
    }

    @Test
    @DisplayName("최신 서류가 MATCHED 면 승인 통과 — 담보도 ACTIVE 로 유효화된다")
    void approvesWithMatchedDocument() {
        SecuredLoan loan = requestedLoan();
        when(loadSecuredLoanPort.findByIdForUpdate(1L)).thenReturn(Optional.of(loan));
        when(loadCollateralDocumentPort.findLatestByLoanId(1L))
                .thenReturn(Optional.of(documentIn(CollateralDocumentStatus.MATCHED)));
        when(saveSecuredLoanPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SecuredLoan approved = service.approve(1L);

        assertThat(approved.getStatus()).isEqualTo(SecuredLoanStatus.APPROVED);
        assertThat(approved.getCollateral().getStatus()).isEqualTo(CollateralStatus.ACTIVE);
    }

    @Test
    @DisplayName("MISMATCHED 서류로는 승인 불가(422) — 대출·담보 상태 불변")
    void blocksMismatchedDocument() {
        SecuredLoan loan = requestedLoan();
        when(loadSecuredLoanPort.findByIdForUpdate(1L)).thenReturn(Optional.of(loan));
        when(loadCollateralDocumentPort.findLatestByLoanId(1L))
                .thenReturn(Optional.of(documentIn(CollateralDocumentStatus.MISMATCHED)));

        assertThatThrownBy(() -> service.approve(1L))
                .isInstanceOf(CollateralDocumentNotMatchedException.class)
                .hasMessageContaining("MISMATCHED");

        assertThat(loan.getStatus()).isEqualTo(SecuredLoanStatus.REQUESTED);
        assertThat(loan.getCollateral().getStatus()).isEqualTo(CollateralStatus.PLEDGED);
        verify(saveSecuredLoanPort, never()).save(any());
    }

    @Test
    @DisplayName("NEEDS_REVIEW·EXTRACTED 서류도 승인 차단 — 리뷰 종결이 먼저다")
    void blocksPendingDocument() {
        when(loadSecuredLoanPort.findByIdForUpdate(1L)).thenReturn(Optional.of(requestedLoan()));

        when(loadCollateralDocumentPort.findLatestByLoanId(1L))
                .thenReturn(Optional.of(documentIn(CollateralDocumentStatus.NEEDS_REVIEW)));
        assertThatThrownBy(() -> service.approve(1L))
                .isInstanceOf(CollateralDocumentNotMatchedException.class);

        when(loadCollateralDocumentPort.findLatestByLoanId(1L))
                .thenReturn(Optional.of(documentIn(CollateralDocumentStatus.EXTRACTED)));
        assertThatThrownBy(() -> service.approve(1L))
                .isInstanceOf(CollateralDocumentNotMatchedException.class);
    }

    @Test
    @DisplayName("서류가 없으면 기존 경로 그대로 승인 통과 (점진 도입)")
    void approvesWithoutDocument() {
        when(loadSecuredLoanPort.findByIdForUpdate(1L)).thenReturn(Optional.of(requestedLoan()));
        when(loadCollateralDocumentPort.findLatestByLoanId(1L)).thenReturn(Optional.empty());
        when(saveSecuredLoanPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SecuredLoan approved = service.approve(1L);

        assertThat(approved.getStatus()).isEqualTo(SecuredLoanStatus.APPROVED);
    }
}
