package github.lms.lemuel.economics.application.port.out;

import github.lms.lemuel.economics.domain.IndicatorValue;

import java.time.LocalDate;

public interface SaveIndicatorValuePort {

    /** (indicator_code, observed_date) UNIQUE upsert — SEED 를 ECOS 가 덮어쓴다. */
    void upsert(IndicatorValue value);

    /**
     * 실 ECOS 최신 관측일({@code latestEcosDate}) 이후의 SEED 행을 제거한다.
     *
     * <p>SEED 시드는 오늘까지 가짜 관측치를 미리 채워두는데, 실 ECOS 는 마지막 영업일까지만 존재한다.
     * 이 후행 SEED 행이 남으면 스냅샷 최신값(헤드라인 카드)이 실데이터가 아니라 시드값을 가리키게 되므로,
     * 동기화 후 이를 잘라 헤드라인이 실 ECOS 최신값을 반영하게 한다.
     *
     * @return 삭제된 SEED 행 수
     */
    int purgeSeedNewerThan(String indicatorCode, LocalDate latestEcosDate);
}
