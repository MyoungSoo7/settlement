package github.lms.lemuel.common.config.jwt;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // 이미 인증이 설정된 경우 필터 스킵 (@WithMockUser 등 테스트 호환)
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);

            try {
                // 토큰 파싱 1회로 통합 (기존: validateToken + getEmail + parseToken = 3회)
                Claims claims = jwtUtil.parseToken(token);
                String email = claims.getSubject();
                String role = claims.get("role", String.class);
                Long uid = claims.get("uid", Long.class); // 구 토큰 호환: null 가능

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                new AuthPrincipal(uid, email, role),
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + role))
                        );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception ignored) {
                // 유효하지 않은 토큰 — 인증 없이 통과 (Spring Security가 401 처리)
            }
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/actuator/health")
            || path.startsWith("/actuator/info")
            || path.startsWith("/swagger-ui")
            || path.startsWith("/v3/api-docs")
            || (path.equals("/auth/login"))
            || (path.startsWith("/auth/dev/"))   // 데모/게스트 토큰 발급 (lemuel.demo.enabled 로 컨트롤러에서 차단)
            || (path.equals("/users"));
    }
}
