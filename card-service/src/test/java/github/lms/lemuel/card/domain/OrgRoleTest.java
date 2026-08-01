package github.lms.lemuel.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * organization-service 이벤트 페이로드 문자열({@code "OWNER"} 등)을 card-service 가 자체
 * 해석할 때 쓰는 값 집합만 확인한다 — organization.. 의존은 ArchUnit 이 이미 금지한다.
 */
class OrgRoleTest {

    @Test
    @DisplayName("역할은 OWNER/MANAGER/STAFF 세 가지, 이벤트 페이로드 문자열로 파싱 가능")
    void hasThreeRolesParsedFromString() {
        assertThat(OrgRole.values()).containsExactly(OrgRole.OWNER, OrgRole.MANAGER, OrgRole.STAFF);
        assertThat(OrgRole.valueOf("OWNER")).isEqualTo(OrgRole.OWNER);
    }
}
