package github.lms.lemuel.closing.application.dto;

import github.lms.lemuel.closing.domain.MonthlyClosingRun;
import github.lms.lemuel.closing.domain.SellerMonthlyClosing;

import java.util.List;

/** 월마감 조회 뷰 — 최신 run 요약 + 셀러 마트 행 전체. */
public record MonthlyClosingView(MonthlyClosingRun run, List<SellerMonthlyClosing> sellers) {
}
