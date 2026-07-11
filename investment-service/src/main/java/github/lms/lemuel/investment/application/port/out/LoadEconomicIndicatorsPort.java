package github.lms.lemuel.investment.application.port.out;

import github.lms.lemuel.investment.domain.EconomicIndicatorSnapshot;

import java.util.List;

/** economics-service 공개 API 로 경제지표 최신값 스냅샷을 조회하는 아웃바운드 포트. */
public interface LoadEconomicIndicatorsPort {

    /** 카탈로그 전체의 최신값(값 없는 지표 제외). 없으면 빈 리스트. */
    List<EconomicIndicatorSnapshot> loadLatest();
}
