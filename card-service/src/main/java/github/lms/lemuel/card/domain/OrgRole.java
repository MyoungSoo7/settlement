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
    STAFF;

    /**
     * 이벤트 페이로드의 역할 문자열을 파싱한다. 계약 밖 값은 원문을 담아 거부한다 —
     * 상류가 역할을 추가했는데 card 가 아직 모르면 <b>적재 시점</b>에 막혀 DLT 로 가야 한다.
     * 그대로 저장하면 실패가 카드 발급 심사(읽는 시점)로 밀려 500 이 되고 원인 이벤트도 남지 않는다.
     */
    public static OrgRole from(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("역할이 없습니다 — organization 이벤트 계약 위반");
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "알 수 없는 역할입니다: " + raw + " (허용: OWNER|MANAGER|STAFF)", e);
        }
    }
}
