package github.lms.lemuel.account.banking.savings.adapter.in.web.dto;

import github.lms.lemuel.account.banking.savings.domain.SavingsInstallment;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 회차 응답 — 연체 여부는 파생값이라 계산해 내려준다(클라이언트가 다시 날짜 비교하지 않도록). */
public record SavingsInstallmentResponse(int round,
                                         BigDecimal amount,
                                         LocalDate dueDate,
                                         LocalDate paidOn,
                                         int overdueDays,
                                         boolean overdue) {

    public static SavingsInstallmentResponse from(SavingsInstallment installment) {
        return new SavingsInstallmentResponse(
                installment.getRound(),
                installment.getAmount(),
                installment.getDueDate(),
                installment.getPaidOn(),
                installment.getOverdueDays(),
                installment.isOverdue());
    }
}
