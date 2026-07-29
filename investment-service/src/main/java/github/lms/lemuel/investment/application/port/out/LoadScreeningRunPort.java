package github.lms.lemuel.investment.application.port.out;

import java.time.LocalDate;
import java.util.Optional;

/** 일일 스크리닝 실행 기록 조회 아웃바운드 포트. */
public interface LoadScreeningRunPort {

    /**
     * 마지막으로 스크리닝을 마친 <b>시세 기준일</b>을 조회한다.
     *
     * <p>추천 세트(산출물)가 아니라 <b>실행 사실</b>이 정본이다 — 통과 종목이 0건이면 추천 행이 남지 않아
     * 산출물로는 "이미 돌았음"을 알 수 없고, 같은 기준일을 매일 재스크리닝하게 된다.
     *
     * @return 실행 기록이 하나도 없으면 빈 값
     */
    Optional<LocalDate> loadLatestScreenedDate();
}
