package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.adapter.out.persistence.OrgMemberProjectionJpaEntity.OrgMemberProjectionId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 멤버 프로젝션 세대(membershipId) 순서 방어 — joined/role_changed/removed 는 서로 다른 토픽이라
 * 도착 순서가 보장되지 않는다(PR #204 리뷰 P1). REMOVED 는 멤버십의 터미널이고 재합류는 새(더 큰)
 * membershipId 를 받으므로, 과거 세대·제거된 같은 세대의 이벤트는 상태를 되살리면 안 된다.
 */
@ExtendWith(MockitoExtension.class)
class OrgProjectionPersistenceAdapterTest {

    private static final Long ORG = 3001L;
    private static final Long USER = 888L;

    @Mock SpringDataOrgProjectionRepository orgRepository;
    @Mock SpringDataOrgMemberProjectionRepository memberRepository;

    OrgProjectionPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new OrgProjectionPersistenceAdapter(orgRepository, memberRepository);
    }

    private void stubExisting(OrgMemberProjectionJpaEntity entity) {
        when(memberRepository.findById(new OrgMemberProjectionId(ORG, USER)))
                .thenReturn(Optional.ofNullable(entity));
    }

    private static OrgMemberProjectionJpaEntity member(String role, boolean active, Long membershipId) {
        OrgMemberProjectionJpaEntity e = new OrgMemberProjectionJpaEntity(3001L, 888L, role, active);
        e.setMembershipId(membershipId);
        return e;
    }

    private OrgMemberProjectionJpaEntity savedEntity() {
        ArgumentCaptor<OrgMemberProjectionJpaEntity> captor =
                ArgumentCaptor.forClass(OrgMemberProjectionJpaEntity.class);
        verify(memberRepository).save(captor.capture());
        return captor.getValue();
    }

    // ── upsertMember (joined / role_changed) ──

    @Test
    @DisplayName("신규 멤버 join → 활성 저장 + 세대 기록")
    void upsert_newMember_savesActiveWithGeneration() {
        stubExisting(null);

        adapter.upsertMember(ORG, USER, "MANAGER", 9001L);

        OrgMemberProjectionJpaEntity saved = savedEntity();
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getRole()).isEqualTo("MANAGER");
        assertThat(saved.getMembershipId()).isEqualTo(9001L);
    }

    @Test
    @DisplayName("★제거된 같은 세대의 늦은 joined/role_changed 는 부활시키지 않는다")
    void upsert_sameGenerationAfterRemoval_isIgnored() {
        stubExisting(member(null, false, 9001L));   // removed 톰스톤(세대 9001)

        adapter.upsertMember(ORG, USER, "MANAGER", 9001L);

        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("★더 낮은 세대의 늦은 이벤트는 무시된다 (재합류 후 과거 join 도착)")
    void upsert_lowerGeneration_isIgnored() {
        stubExisting(member("STAFF", true, 9002L));   // 이미 재합류(세대 9002)

        adapter.upsertMember(ORG, USER, "MANAGER", 9001L);

        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("재합류(새 세대) join 은 제거를 이기고 활성화한다")
    void upsert_higherGeneration_reactivates() {
        stubExisting(member(null, false, 9001L));   // removed 톰스톤

        adapter.upsertMember(ORG, USER, "STAFF", 9002L);

        OrgMemberProjectionJpaEntity saved = savedEntity();
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getRole()).isEqualTo("STAFF");
        assertThat(saved.getMembershipId()).isEqualTo(9002L);
    }

    @Test
    @DisplayName("같은 세대 활성 멤버의 role_changed 는 정상 반영된다")
    void upsert_sameGenerationActive_updatesRole() {
        stubExisting(member("STAFF", true, 9001L));

        adapter.upsertMember(ORG, USER, "MANAGER", 9001L);

        OrgMemberProjectionJpaEntity saved = savedEntity();
        assertThat(saved.getRole()).isEqualTo("MANAGER");
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    @DisplayName("레거시 행(세대 null)에는 새 이벤트가 그대로 적용되고 세대가 채워진다")
    void upsert_legacyRowWithoutGeneration_applies() {
        stubExisting(member("STAFF", true, null));

        adapter.upsertMember(ORG, USER, "MANAGER", 9001L);

        OrgMemberProjectionJpaEntity saved = savedEntity();
        assertThat(saved.getRole()).isEqualTo("MANAGER");
        assertThat(saved.getMembershipId()).isEqualTo(9001L);
    }

    @Test
    @DisplayName("세대 없는(created 경유 OWNER) 등록은 톰스톤을 이기지 못한다")
    void upsert_nullGeneration_cannotResurrectTombstone() {
        stubExisting(member(null, false, 9001L));

        adapter.upsertMember(ORG, USER, "OWNER", null);

        verify(memberRepository, never()).save(any());
    }

    // ── deactivateMember (removed) ──

    @Test
    @DisplayName("★제거가 합류보다 먼저 도착하면 톰스톤(active=false)을 남긴다")
    void deactivate_missingRow_insertsTombstone() {
        stubExisting(null);

        adapter.deactivateMember(ORG, USER, 9001L);

        OrgMemberProjectionJpaEntity saved = savedEntity();
        assertThat(saved.isActive()).isFalse();
        assertThat(saved.getMembershipId()).isEqualTo(9001L);
    }

    @Test
    @DisplayName("★재합류(더 높은 세대) 뒤에 도착한 과거 제거 이벤트는 무시된다")
    void deactivate_staleRemoval_isIgnored() {
        stubExisting(member("STAFF", true, 9002L));

        adapter.deactivateMember(ORG, USER, 9001L);

        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("현재 세대의 제거는 비활성화한다 (역할·이력은 보존)")
    void deactivate_currentGeneration_deactivates() {
        stubExisting(member("MANAGER", true, 9001L));

        adapter.deactivateMember(ORG, USER, 9001L);

        OrgMemberProjectionJpaEntity saved = savedEntity();
        assertThat(saved.isActive()).isFalse();
        assertThat(saved.getRole()).isEqualTo("MANAGER");
        assertThat(saved.getMembershipId()).isEqualTo(9001L);
    }

    @Test
    @DisplayName("레거시 행(세대 null)의 제거는 그대로 비활성화하고 세대를 채운다")
    void deactivate_legacyRow_deactivates() {
        stubExisting(member("STAFF", true, null));

        adapter.deactivateMember(ORG, USER, 9001L);

        OrgMemberProjectionJpaEntity saved = savedEntity();
        assertThat(saved.isActive()).isFalse();
        assertThat(saved.getMembershipId()).isEqualTo(9001L);
    }
}
