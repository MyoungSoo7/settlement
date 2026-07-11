package github.lms.lemuel.investment.application.port.out;

import github.lms.lemuel.investment.domain.DailyClose;

import java.util.List;

/** market-service 공개 API 로 일별 종가 시계열을 조회하는 아웃바운드 포트. */
public interface LoadDailyClosesPort {

    /** 최근 52주 창을 덮는 일별 종가(정렬 무관). 종목 미등록·데이터 없음이면 빈 리스트. */
    List<DailyClose> loadRecentYear(String stockCode);
}
