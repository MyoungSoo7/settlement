package github.lms.lemuel.ai.audit.application.port.out;

import java.util.Map;

/**
 * 감사 기록 아웃바운드 포트 — 민감 사용자 작업(대화 삭제 등)을 audit_logs 에 남긴다.
 *
 * <p>기록 실패는 본 작업을 깨뜨리지 않는다(구현체가 삼키고 warn). 수집 트리거는 감사 유실이 나더라도
 * 본 배치는 진행되는 편이 합리적이다 — 감사는 부가 추적이지 트리거의 전제 조건이 아니다.
 *
 * <p>헥사고날: inbound 어댑터(컨트롤러)가 이 포트를 통해서만 감사에 접근한다 —
 * 컨트롤러가 리포지토리를 직접 만지지 않도록(공통 컴포넌트 경유).
 */
public interface RecordAuditPort {

    /**
     * @param action       AuditAction 성 문자열 (예: COLLECT_TRIGGERED)
     * @param resourceType 대상 리소스 종류 (예: Conversation)
     * @param resourceId   대상 식별자 (예: 대화 UUID)
     * @param detail       작업 상세 (JSON 직렬화됨, null/빈 맵이면 detail_json 은 NULL)
     */
    void record(String action, String resourceType, String resourceId, Map<String, Object> detail);
}
