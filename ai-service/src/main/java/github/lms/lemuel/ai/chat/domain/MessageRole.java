package github.lms.lemuel.ai.chat.domain;

/** 대화 메시지의 발화 주체. LLM API 의 role 과 1:1 대응한다. */
public enum MessageRole {
    USER,
    ASSISTANT
}
