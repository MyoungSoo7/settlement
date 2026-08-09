package github.lms.lemuel.account.banking.pension.adapter.in.web.dto;

import java.math.BigDecimal;

/** 운용지시 변경 요청 — 원리금보장 상품 1개(비중 배분 없음)로 의도적으로 최소화한 모델. */
public record ChangeInvestmentInstructionRequest(String productName, BigDecimal rate) {
}
