package github.lms.lemuel.integrity.domain;

/**
 * INV-10 이벤트 회계의 소비측 분자 — {@code processed_events} 를
 * (consumer_group, event_type) 로 묶은 건수. 발행측(order outbox PUBLISHED 건수)과의
 * 대조·판정은 copilot MCP {@code event_accounting} 이 수행한다 (settlement 은 자기 숫자만 노출).
 */
public record ProcessedEventCount(String consumerGroup, String eventType, long count) {
}
