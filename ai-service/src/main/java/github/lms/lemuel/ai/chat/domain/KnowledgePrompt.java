package github.lms.lemuel.ai.chat.domain;

import java.util.List;

/**
 * 검색된 근거를 system prompt 에 붙이는 규칙 — 순수 도메인(문자열 조립만).
 *
 * <p><b>무행동 착지(no-op landing):</b> 근거가 0건이면 <b>원본 프롬프트를 그대로 반환</b>한다.
 * 지식베이스가 비어 있거나 RAG 가 꺼져 있는 동안 LLM 에 실려 가는 바이트가 Phase 1 과 완전히
 * 동일하다 — 즉 이 기능은 "지식을 넣기 전까지 아무 일도 하지 않는다"가 구조적으로 보장된다.
 * (검색 실패 시 강등의 착지점도 여기다)
 *
 * <p><b>왜 user 메시지가 아니라 system prompt 에 붙이나:</b> 근거를 사용자 발화에 끼워 넣으면
 * 그 합성 텍스트가 대화 이력에 저장되고 다음 턴의 컨텍스트 윈도까지 오염시킨다(토큰 낭비 +
 * 이력 조회 UI 에 참고 자료가 노출). system prompt 는 매 요청 새로 조립되고 저장되지 않으므로
 * 근거는 그 턴에만 살아 있다.
 *
 * <p>근거를 붙일 때 <b>환각 금지 지시를 함께</b> 넣는다. RAG 의 실패 모드는 "못 찾음"이 아니라
 * "엉뚱한 자료를 찾아 그럴듯하게 지어냄"이므로, 자료가 있을 때일수록 경계 문구가 필요하다.
 */
public final class KnowledgePrompt {

    private static final String INSTRUCTION = """
            [참고 자료 사용 규칙]
            아래 참고 자료에서 답을 찾을 수 있으면 그 내용만을 근거로 답하고, 어떤 자료를 근거로 삼았는지 제목으로 밝히세요.
            참고 자료에 없는 내용은 추측하거나 지어내지 말고 "제공된 자료에서 확인할 수 없습니다"라고 답하세요.
            참고 자료가 질문과 무관하면 무시하고, 일반 지식으로 답할 수 있는 범위에서만 답하세요.""";

    private KnowledgePrompt() {
    }

    /**
     * @param systemPrompt 설정된 원본 system prompt
     * @param contexts     검색된 근거 (비어 있으면 원본을 그대로 반환)
     */
    public static String augment(String systemPrompt, List<RetrievedContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return systemPrompt;
        }
        StringBuilder sb = new StringBuilder(systemPrompt == null ? "" : systemPrompt);
        sb.append("\n\n").append(INSTRUCTION);
        int index = 1;
        for (RetrievedContext context : contexts) {
            sb.append("\n\n--- 자료 ").append(index++).append(": ").append(context.title()).append(" ---\n")
                    .append(context.content());
        }
        return sb.toString();
    }
}
