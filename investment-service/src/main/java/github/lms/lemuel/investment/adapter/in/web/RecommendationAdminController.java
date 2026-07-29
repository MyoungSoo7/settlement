package github.lms.lemuel.investment.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.investment.application.port.in.RunDailyScreeningUseCase;
import github.lms.lemuel.investment.application.port.in.RunDailyScreeningUseCase.DailyScreeningReport;
import github.lms.lemuel.investment.application.port.in.ScreenRecommendationsUseCase;
import github.lms.lemuel.investment.domain.ScreeningTrigger;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 종목 추천 스크리닝 <b>수동 트리거</b>(운영·배포 직후 즉시 채우기용). 크론과 동일한 스크리닝을 즉석 실행한다.
 *
 * <p>상태를 바꾸는 운영 액션이므로 ADMIN 만 호출할 수 있다(일반 조회 {@code GET /recommendations} 와 분리).
 *
 * <p><b>date 미지정이면 크론과 같은 판정 경로</b>({@link RunDailyScreeningUseCase})로 돈다 — 추천일은 실행일이
 * 아니라 시세 기준일이다. 실행일로 세트를 만들면 크론이 그 날짜를 최신으로 오인해 이후 실행을 통째로
 * 건너뛴다. 특정 날짜를 강제로 다시 뽑아야 할 때만(백필·데이터 정정) {@code date} 를 명시한다.
 */
@RestController
@RequestMapping("/api/investment/recommendations")
public class RecommendationAdminController {

    private final ScreenRecommendationsUseCase screenRecommendationsUseCase;
    private final RunDailyScreeningUseCase runDailyScreeningUseCase;

    public RecommendationAdminController(ScreenRecommendationsUseCase screenRecommendationsUseCase,
                                         RunDailyScreeningUseCase runDailyScreeningUseCase) {
        this.screenRecommendationsUseCase = screenRecommendationsUseCase;
        this.runDailyScreeningUseCase = runDailyScreeningUseCase;
    }

    /**
     * 규칙 스크리닝을 즉시 실행한다.
     *
     * <ul>
     *   <li>{@code date} 미지정 — 크론과 동일 판정: 새 종가가 없으면 스킵({@code decision} 으로 사유 반환).</li>
     *   <li>{@code date} 지정 — 그 날짜로 강제 스크리닝(백필).</li>
     * </ul>
     */
    @PostMapping("/screen")
    public ResponseEntity<ScreeningResult> screen(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication authentication) {
        requireAdmin(authentication);
        if (date == null) {
            DailyScreeningReport report = runDailyScreeningUseCase.run();
            return ResponseEntity.ok(new ScreeningResult(
                    report.trigger().quoteBaseDate(), report.screenedCount(),
                    report.trigger().decision().name()));
        }
        int count = screenRecommendationsUseCase.screen(date);
        return ResponseEntity.ok(new ScreeningResult(date, count, ScreeningTrigger.Decision.SCREEN.name()));
    }

    private static void requireAdmin(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthPrincipal principal)
                || !"ADMIN".equals(principal.role())) {
            throw new AccessDeniedException("종목 스크리닝 수동 실행은 ADMIN 만 가능합니다.");
        }
    }

    /**
     * 스크리닝 실행 결과 — 추천일(=시세 기준일), 저장된 종목 수, 판정 사유.
     *
     * <p>스킵이면 {@code count=0} 이고 {@code decision} 이 사유를 알려준다
     * ({@code SKIP_UP_TO_DATE} = 새 종가 없음, {@code SKIP_NO_QUOTES} = 시세 조회 실패).
     */
    public record ScreeningResult(LocalDate recommendedDate, int count, String decision) {
    }
}
