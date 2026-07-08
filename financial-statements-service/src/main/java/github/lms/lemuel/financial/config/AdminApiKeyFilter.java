package github.lms.lemuel.financial.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * DART 수집 트리거(/admin/financial/**) 공유 시크릿 게이트.
 *
 * <p>X-Internal-Api-Key 헤더가 {@code app.financial.internal-api-key} 와 일치해야 통과.
 * 시크릿 미설정 시 통과+경고 — 로컬 개발 편의를 위한 order-service InternalApiKeyFilter 와
 * 동일한 시맨틱(운영에서는 반드시 설정 + gateway 미라우팅으로 외부 미노출).
 */
@Component
public class AdminApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminApiKeyFilter.class);
    static final String HEADER = "X-Internal-Api-Key";

    private final String apiKey;

    public AdminApiKeyFilter(@Value("${app.financial.internal-api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/admin/financial/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("app.financial.internal-api-key 미설정 — /admin/financial/** 무게이팅 통과 (로컬 전용, 운영 설정 필수)");
            filterChain.doFilter(request, response);
            return;
        }
        if (!apiKey.equals(request.getHeader(HEADER))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "invalid internal api key");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
