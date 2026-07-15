package github.lms.lemuel.investment.application.port.in;

import java.time.LocalDate;

/** 유니버스 종목을 규칙 스크리닝해 해당 추천일의 세트를 생성·저장하는 유스케이스. */
public interface ScreenRecommendationsUseCase {

    /**
     * {@code asOf} 추천일 기준으로 유니버스를 스크리닝하고 통과 종목 세트를 저장한다.
     *
     * @param asOf 추천일(스크리닝 실행일)
     * @return 저장된 추천 종목 수(0 이면 통과 종목 없음)
     */
    int screen(LocalDate asOf);
}
