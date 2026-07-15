package github.lms.lemuel.investment.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.investment.application.port.in.ScreenRecommendationsUseCase;
import github.lms.lemuel.investment.config.ScreeningProperties;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 종목 추천 스크리닝 <b>수동 트리거</b>(운영·배포 직후 즉시 채우기용). 크론과 동일한 스크리닝을 즉석 실행한다.
 *
 * <p>상태를 바꾸는 운영 액션이므로 ADMIN 만 호출할 수 있다(일반 조회 {@code GET /recommendations} 와 분리).
 */
@RestController
@RequestMapping("/api/investment/recommendations")
public class RecommendationAdminController {

    private final ScreenRecommendationsUseCase screenRecommendationsUseCase;
    private final ScreeningProperties properties;

    public RecommendationAdminController(ScreenRecommendationsUseCase screenRecommendationsUseCase,
                                         ScreeningProperties properties) {
        this.screenRecommendationsUseCase = screenRecommendationsUseCase;
        this.properties = properties;
    }

    /** 규칙 스크리닝을 즉시 실행해 해당 추천일 세트를 생성한다. date 미지정 시 설정 시간대 기준 오늘. */
    @PostMapping("/screen")
    public ResponseEntity<ScreeningResult> screen(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication authentication) {
        requireAdmin(authentication);
        LocalDate asOf = date != null ? date : LocalDate.now(ZoneId.of(properties.zone()));
        int count = screenRecommendationsUseCase.screen(asOf);
        return ResponseEntity.ok(new ScreeningResult(asOf, count));
    }

    private static void requireAdmin(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthPrincipal principal)
                || !"ADMIN".equals(principal.role())) {
            throw new AccessDeniedException("종목 스크리닝 수동 실행은 ADMIN 만 가능합니다.");
        }
    }

    /** 스크리닝 실행 결과 — 추천일과 저장된 종목 수. */
    public record ScreeningResult(LocalDate recommendedDate, int count) {
    }
}
