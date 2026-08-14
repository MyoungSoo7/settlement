package github.lms.lemuel.closing.adapter.in.web.response;

import github.lms.lemuel.closing.application.dto.MonthlyClosingView;

import java.util.List;

/** 월마감 조회 응답 — run 요약 + 셀러 마트. */
public record MonthlyClosingResponse(MonthlyClosingRunResponse run,
                                     List<SellerMonthlyClosingResponse> sellers) {

    public static MonthlyClosingResponse from(MonthlyClosingView view) {
        return new MonthlyClosingResponse(
                MonthlyClosingRunResponse.from(view.run()),
                view.sellers().stream().map(SellerMonthlyClosingResponse::from).toList());
    }
}
