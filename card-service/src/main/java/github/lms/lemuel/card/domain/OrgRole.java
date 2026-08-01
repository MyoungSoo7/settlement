package github.lms.lemuel.card.domain;

/**
 * 조직 내 역할 — card-service 가 <b>자체 정의</b>한다.
 *
 * <p>organization-service 에도 동일한 개념의 역할이 있지만, ArchUnit
 * ({@code card_는_타_서비스_도메인에_코드의존하지_않는다})이 {@code github.lms.lemuel.organization..}
 * 의존을 금지하므로 그 타입을 import 할 수 없다. 두 서비스는 Kafka 이벤트 페이로드의 문자열
 * (예: {@code "OWNER"})로만 연결되고, card-service 는 수신한 문자열을 이 enum 으로 자체 해석한다.
 */
public enum OrgRole {
    OWNER,
    MANAGER,
    STAFF
}
