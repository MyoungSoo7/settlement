package github.lms.lemuel.ai.audit.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.ai.audit.application.port.out.RecordAuditPort;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * {@link RecordAuditPort} 구현 — audit_logs 에 감사행을 남긴다.
 *
 * <p>정책:
 * <ul>
 *   <li><b>REQUIRES_NEW</b> — 감사 기록을 호출자 트랜잭션과 독립시킨다(호출자가 롤백해도 감사는 남음).</li>
 *   <li><b>실패 무해</b> — 기록 실패(직렬화·DB 오류)는 삼키고 warn 만 남긴다. 대화 삭제 같은
 *       사용자 작업은 감사 유실보다 본 작업 진행이 우선이다.</li>
 *   <li><b>actor 해석</b> — JWT 인증 주체({@link AuthPrincipal})가 있으면 userId/email 을,
 *       /admin 경로(AdminApiKeyFilter 게이트, 인증 주체 없음)면 actorId 는 NULL 로 둔다.
 *       IP/User-Agent 는 요청 스레드에서 읽는다.</li>
 * </ul>
 *
 * <p>ai 는 shared-common 을 의존하지만 제한 스캔(common.config.jwt 만)이라 공통 audit 스택을
 * 재사용할 수 없어 이 어댑터를 자체 구현한다(JWT 타입 {@link AuthPrincipal} 만 shared-common 에서 참조).
 */
@Component
public class AuditRecordingAdapter implements RecordAuditPort {

    private static final Logger log = LoggerFactory.getLogger(AuditRecordingAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuditLogRepository repository;

    public AuditRecordingAdapter(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String resourceType, String resourceId, Map<String, Object> detail) {
        try {
            AuditLogJpaEntity entity = new AuditLogJpaEntity();
            entity.setAction(action);
            entity.setResourceType(resourceType);
            entity.setResourceId(resourceId);
            entity.setDetailJson(toJson(detail));
            entity.setCreatedAt(LocalDateTime.now());
            applyActor(entity);
            repository.save(entity);
        } catch (Exception e) {
            log.warn("감사 로그 기록 실패 — 본 작업은 계속한다. action={}, resourceId={}", action, resourceId, e);
        }
    }

    private String toJson(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(detail);
        } catch (Exception e) {
            // detail 직렬화가 깨져도 행위 자체는 기록한다 — 상세만 손실.
            return "{\"error\":\"audit_detail_serialization_failed\"}";
        }
    }

    private void applyActor(AuditLogJpaEntity entity) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            Object principal = auth.getPrincipal();
            if (principal instanceof AuthPrincipal ap) {
                entity.setActorId(ap.userId());
                entity.setActorEmail(ap.email());
            } else if (principal != null) {
                entity.setActorEmail(principal.toString());
            }
        }
        HttpServletRequest request = currentRequest();
        if (request != null) {
            entity.setIpAddress(extractIp(request));
            entity.setUserAgent(request.getHeader("User-Agent"));
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;   // 배경 스레드 등 요청 밖 호출 — actor 없이 기록.
    }

    private String extractIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }
}
