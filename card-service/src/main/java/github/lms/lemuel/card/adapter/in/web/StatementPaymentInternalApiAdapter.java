package github.lms.lemuel.card.adapter.in.web;

import github.lms.lemuel.card.application.port.in.PayStatementUseCase;
import github.lms.lemuel.card.application.port.in.PayStatementUseCase.PayStatementCommand;
import github.lms.lemuel.card.domain.CardStatement;
import github.lms.lemuel.card.domain.StatementStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 명세서 상환 내부 API 어댑터.
 *
 * <p>경로: {@code POST /internal/api/v1/statements/{id}/payments}
 *
 * <p>보안: {@code /internal/**} 경로는 내부망에서만 접근 가능. API Gateway 가 외부 미노출.
 * {@code paymentId} 가 멱등 키 — 동일 paymentId 재전송은 no-op 으로 처리된다.
 */
@RestController
@RequestMapping("/internal/api/v1/statements")
public class StatementPaymentInternalApiAdapter {

    private final PayStatementUseCase payStatementUseCase;

    public StatementPaymentInternalApiAdapter(PayStatementUseCase payStatementUseCase) {
        this.payStatementUseCase = payStatementUseCase;
    }

    @PostMapping("/{statementId}/payments")
    public ResponseEntity<StatementPaymentResponse> pay(
            @PathVariable Long statementId,
            @Valid @RequestBody StatementPaymentRequest request) {

        CardStatement statement = payStatementUseCase.pay(
                new PayStatementCommand(statementId, request.paymentId(), request.amount()));

        return ResponseEntity.ok(new StatementPaymentResponse(
                statement.getId(),
                statement.getCardAccountId(),
                statement.getBillingYearMonth().toString(),
                statement.getTotalAmount().toPlainString(),
                statement.getPaidAmount().toPlainString(),
                statement.unpaidAmount().toPlainString(),
                statement.getStatus().name()
        ));
    }

    // ── DTO ──

    /**
     * 납부 요청 본문.
     *
     * @param paymentId 멱등 자연키 — 동일 paymentId 재전송은 no-op
     * @param amount    납부 금액(양수)
     */
    public record StatementPaymentRequest(
            @NotBlank String paymentId,
            @NotNull @DecimalMin("0.01") BigDecimal amount
    ) {
    }

    /**
     * 납부 응답.
     *
     * @param statementId      명세서 ID
     * @param cardAccountId    카드계정 ID
     * @param billingYearMonth 청구주기 (YYYY-MM)
     * @param totalAmount      청구 총액(문자열, DATA-STANDARD N5)
     * @param paidAmount       납부 누적액(문자열, DATA-STANDARD N5)
     * @param unpaidAmount     미납 잔액(문자열, DATA-STANDARD N5)
     * @param status           납부 후 명세서 상태
     */
    public record StatementPaymentResponse(
            Long statementId,
            Long cardAccountId,
            String billingYearMonth,
            String totalAmount,
            String paidAmount,
            String unpaidAmount,
            String status
    ) {
    }
}
