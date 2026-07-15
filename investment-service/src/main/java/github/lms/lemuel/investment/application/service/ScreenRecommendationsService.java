package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.port.in.GetBeginnerCheckUseCase;
import github.lms.lemuel.investment.application.port.in.ScreenRecommendationsUseCase;
import github.lms.lemuel.investment.application.port.out.SaveStockRecommendationPort;
import github.lms.lemuel.investment.config.ScreeningProperties;
import github.lms.lemuel.investment.domain.BeginnerInvestmentCheck;
import github.lms.lemuel.investment.domain.RecommendationScreeningPolicy;
import github.lms.lemuel.investment.domain.ScreenedPick;
import github.lms.lemuel.investment.domain.StockRecommendation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 일일 규칙 스크리닝 서비스 — 유니버스 각 종목을 {@link GetBeginnerCheckUseCase} 로 5규칙 평가하고,
 * 통과 종목을 투자점수순(옵션: 업종 분산)으로 상위 N 선정해 해당 추천일 세트로 저장한다.
 *
 * <p>개별 종목 조회 실패(회계 미존재 404·원천 장애 등)는 전체 실행을 죽이지 않고 해당 종목만 건너뛴다.
 * 저장은 {@link SaveStockRecommendationPort#replaceForDate}(삭제 후 삽입)로 같은 날 재실행에 멱등하다.
 */
@Service
public class ScreenRecommendationsService implements ScreenRecommendationsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ScreenRecommendationsService.class);

    private final GetBeginnerCheckUseCase getBeginnerCheckUseCase;
    private final SaveStockRecommendationPort saveStockRecommendationPort;
    private final ScreeningProperties properties;
    private final RecommendationScreeningPolicy policy = new RecommendationScreeningPolicy();

    public ScreenRecommendationsService(GetBeginnerCheckUseCase getBeginnerCheckUseCase,
                                        SaveStockRecommendationPort saveStockRecommendationPort,
                                        ScreeningProperties properties) {
        this.getBeginnerCheckUseCase = getBeginnerCheckUseCase;
        this.saveStockRecommendationPort = saveStockRecommendationPort;
        this.properties = properties;
    }

    @Override
    public int screen(LocalDate asOf) {
        List<ScreenedPick> passed = new ArrayList<>();
        for (ScreeningProperties.UniverseEntry entry : properties.universe()) {
            try {
                // 예산 없이(가격 레벨 전용) 5규칙 평가 — 추천 세트는 개인 예산과 무관한 규칙 산출물이다.
                BeginnerInvestmentCheck check = getBeginnerCheckUseCase.getCheck(entry.code(), null);
                policy.evaluate(check, entry.sector()).ifPresent(passed::add);
            } catch (RuntimeException e) {
                log.warn("[screening] {} 스킵 — {}", entry.code(), e.getMessage());
            }
        }

        List<StockRecommendation> set = select(passed, asOf);
        saveStockRecommendationPort.replaceForDate(asOf, set);
        log.info("[screening] {} 추천 세트 {}종목 저장 (후보 {} · 통과 {})",
                asOf, set.size(), properties.universe().size(), passed.size());
        return set.size();
    }

    /** 투자점수 내림차순 정렬 → (옵션) 업종당 최고 1개 → 상위 maxPicks 선정, displayOrder 1..N 배정. */
    private List<StockRecommendation> select(List<ScreenedPick> passed, LocalDate asOf) {
        List<ScreenedPick> ranked = passed.stream()
                .sorted(Comparator.comparingInt(ScreenedPick::score).reversed())
                .toList();

        List<ScreenedPick> chosen;
        if (properties.sectorDiversify()) {
            // ranked 가 점수 내림차순이라 업종별 첫 등장이 그 업종 최고 점수.
            Map<String, ScreenedPick> bestBySector = new LinkedHashMap<>();
            for (ScreenedPick pick : ranked) {
                bestBySector.putIfAbsent(pick.sector(), pick);
            }
            chosen = new ArrayList<>(bestBySector.values());
        } else {
            chosen = ranked;
        }

        int limit = Math.min(properties.maxPicks(), chosen.size());
        List<StockRecommendation> set = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            set.add(chosen.get(i).toRecommendation(asOf, i + 1));
        }
        return set;
    }
}
