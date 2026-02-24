package github.lms.lemuel.user.adapter.out.persistence;

import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * UserPersistenceAdapter TDD Test
 *
 * 테스트 범위:
 * 1. findById - ID로 사용자 조회
 * 2. findByEmail - 이메일로 사용자 조회
 * 3. save - 사용자 저장
 * 4. existsByEmail - 이메일 존재 여부 확인
 * 5. Domain <-> Entity 매핑 통합
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserPersistenceAdapter 영속성 어댑터")
class UserPersistenceAdapterTest {

    @Mock
    private SpringDataUserJpaRepository userJpaRepository;

    @Mock
    private UserPersistenceMapper mapper;

    @InjectMocks
    private UserPersistenceAdapter userPersistenceAdapter;

    @Nested
    @DisplayName("findById 테스트")
    class FindByIdTest {

        @Test
        @DisplayName("존재하는 ID로 조회하면 도메인 User를 반환한다")
        void findById_WithExistingId_ReturnsDomainUser() {
            // given
            Long userId = 1L;
            UserJpaEntity entity = createJpaEntity(userId, "user@example.com", "hash", "USER");
            User domainUser = createDomainUser(userId, "user@example.com", "hash", UserRole.USER);

            given(userJpaRepository.findById(userId)).willReturn(Optional.of(entity));
            given(mapper.toDomain(entity)).willReturn(domainUser);

            // when
            Optional<User> result = userPersistenceAdapter.findById(userId);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(userId);
            assertThat(result.get().getEmail()).isEqualTo("user@example.com");

            then(userJpaRepository).should().findById(userId);
            then(mapper).should().toDomain(entity);
        }

        @Test
        @DisplayName("존재하지 않는 ID로 조회하면 Optional.empty()를 반환한다")
        void findById_WithNonExistentId_ReturnsEmpty() {
            // given
            Long userId = 999L;
            given(userJpaRepository.findById(userId)).willReturn(Optional.empty());

            // when
            Optional<User> result = userPersistenceAdapter.findById(userId);

            // then
            assertThat(result).isEmpty();

            then(userJpaRepository).should().findById(userId);
            then(mapper).should(never()).toDomain(any());
        }
    }

    @Nested
    @DisplayName("findByEmail 테스트")
    class FindByEmailTest {

        @Test
        @DisplayName("존재하는 이메일로 조회하면 도메인 User를 반환한다")
        void findByEmail_WithExistingEmail_ReturnsDomainUser() {
            // given
            String email = "existing@example.com";
            UserJpaEntity entity = createJpaEntity(1L, email, "hash", "USER");
            User domainUser = createDomainUser(1L, email, "hash", UserRole.USER);

            given(userJpaRepository.findByEmail(email)).willReturn(Optional.of(entity));
            given(mapper.toDomain(entity)).willReturn(domainUser);

            // when
            Optional<User> result = userPersistenceAdapter.findByEmail(email);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().getEmail()).isEqualTo(email);

            then(userJpaRepository).should().findByEmail(email);
            then(mapper).should().toDomain(entity);
        }

        @Test
        @DisplayName("존재하지 않는 이메일로 조회하면 Optional.empty()를 반환한다")
        void findByEmail_WithNonExistentEmail_ReturnsEmpty() {
            // given
            String email = "nonexistent@example.com";
            given(userJpaRepository.findByEmail(email)).willReturn(Optional.empty());

            // when
            Optional<User> result = userPersistenceAdapter.findByEmail(email);

            // then
            assertThat(result).isEmpty();

            then(userJpaRepository).should().findByEmail(email);
            then(mapper).should(never()).toDomain(any());
        }

        @Test
        @DisplayName("다양한 이메일 형식으로 조회할 수 있다")
        void findByEmail_WithVariousEmailFormats_Works() {
            // given
            String[] emails = {
                    "test@example.com",
                    "user.name@domain.co.kr",
                    "admin+tag@company.com"
            };

            for (String email : emails) {
                UserJpaEntity entity = createJpaEntity(1L, email, "hash", "USER");
                User domainUser = createDomainUser(1L, email, "hash", UserRole.USER);

                given(userJpaRepository.findByEmail(email)).willReturn(Optional.of(entity));
                given(mapper.toDomain(entity)).willReturn(domainUser);

                // when
                Optional<User> result = userPersistenceAdapter.findByEmail(email);

                // then
                assertThat(result).isPresent();
                assertThat(result.get().getEmail()).isEqualTo(email);
            }
        }
    }

    @Nested
    @DisplayName("save 테스트")
    class SaveTest {

        @Test
        @DisplayName("새로운 사용자를 저장하고 ID가 할당된 도메인 User를 반환한다")
        void save_NewUser_ReturnsUserWithId() {
            // given
            User newUser = createDomainUser(null, "new@example.com", "hash", UserRole.USER);
            UserJpaEntity entityToSave = createJpaEntity(null, "new@example.com", "hash", "USER");
            UserJpaEntity savedEntity = createJpaEntity(1L, "new@example.com", "hash", "USER");
            User savedUser = createDomainUser(1L, "new@example.com", "hash", UserRole.USER);

            given(mapper.toEntity(newUser)).willReturn(entityToSave);
            given(userJpaRepository.save(entityToSave)).willReturn(savedEntity);
            given(mapper.toDomain(savedEntity)).willReturn(savedUser);

            // when
            User result = userPersistenceAdapter.save(newUser);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getEmail()).isEqualTo("new@example.com");

            then(mapper).should().toEntity(newUser);
            then(userJpaRepository).should().save(entityToSave);
            then(mapper).should().toDomain(savedEntity);
        }

        @Test
        @DisplayName("기존 사용자를 업데이트하고 도메인 User를 반환한다")
        void save_ExistingUser_ReturnsUpdatedUser() {
            // given
            User existingUser = createDomainUser(5L, "existing@example.com", "newHash", UserRole.ADMIN);
            UserJpaEntity entityToUpdate = createJpaEntity(5L, "existing@example.com", "newHash", "ADMIN");
            UserJpaEntity updatedEntity = createJpaEntity(5L, "existing@example.com", "newHash", "ADMIN");
            User updatedUser = createDomainUser(5L, "existing@example.com", "newHash", UserRole.ADMIN);

            given(mapper.toEntity(existingUser)).willReturn(entityToUpdate);
            given(userJpaRepository.save(entityToUpdate)).willReturn(updatedEntity);
            given(mapper.toDomain(updatedEntity)).willReturn(updatedUser);

            // when
            User result = userPersistenceAdapter.save(existingUser);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(5L);
            assertThat(result.getPasswordHash()).isEqualTo("newHash");
            assertThat(result.getRole()).isEqualTo(UserRole.ADMIN);
        }

        @Test
        @DisplayName("모든 역할의 사용자를 저장할 수 있다")
        void save_UsersWithDifferentRoles_Success() {
            // given & when & then
            for (UserRole role : UserRole.values()) {
                User user = createDomainUser(null, "user@example.com", "hash", role);
                UserJpaEntity entity = createJpaEntity(null, "user@example.com", "hash", role.name());
                UserJpaEntity savedEntity = createJpaEntity(1L, "user@example.com", "hash", role.name());
                User savedUser = createDomainUser(1L, "user@example.com", "hash", role);

                given(mapper.toEntity(user)).willReturn(entity);
                given(userJpaRepository.save(entity)).willReturn(savedEntity);
                given(mapper.toDomain(savedEntity)).willReturn(savedUser);

                User result = userPersistenceAdapter.save(user);

                assertThat(result.getRole()).isEqualTo(role);
            }
        }
    }

    @Nested
    @DisplayName("existsByEmail 테스트")
    class ExistsByEmailTest {

        @Test
        @DisplayName("존재하는 이메일이면 true를 반환한다")
        void existsByEmail_WithExistingEmail_ReturnsTrue() {
            // given
            String email = "existing@example.com";
            given(userJpaRepository.existsByEmail(email)).willReturn(true);

            // when
            boolean result = userPersistenceAdapter.existsByEmail(email);

            // then
            assertThat(result).isTrue();
            then(userJpaRepository).should().existsByEmail(email);
        }

        @Test
        @DisplayName("존재하지 않는 이메일이면 false를 반환한다")
        void existsByEmail_WithNonExistentEmail_ReturnsFalse() {
            // given
            String email = "nonexistent@example.com";
            given(userJpaRepository.existsByEmail(email)).willReturn(false);

            // when
            boolean result = userPersistenceAdapter.existsByEmail(email);

            // then
            assertThat(result).isFalse();
            then(userJpaRepository).should().existsByEmail(email);
        }

        @Test
        @DisplayName("이메일 존재 확인 시 매퍼를 사용하지 않는다")
        void existsByEmail_DoesNotUseMapper() {
            // given
            String email = "test@example.com";
            given(userJpaRepository.existsByEmail(email)).willReturn(true);

            // when
            userPersistenceAdapter.existsByEmail(email);

            // then
            then(mapper).should(never()).toDomain(any());
            then(mapper).should(never()).toEntity(any());
        }
    }

    @Nested
    @DisplayName("통합 시나리오 테스트")
    class IntegrationScenarioTest {

        @Test
        @DisplayName("사용자 생성 후 이메일로 조회하는 시나리오")
        void saveAndFindByEmail_Scenario() {
            // given - save
            User newUser = createDomainUser(null, "scenario@example.com", "hash", UserRole.USER);
            UserJpaEntity entityToSave = createJpaEntity(null, "scenario@example.com", "hash", "USER");
            UserJpaEntity savedEntity = createJpaEntity(10L, "scenario@example.com", "hash", "USER");
            User savedUser = createDomainUser(10L, "scenario@example.com", "hash", UserRole.USER);

            given(mapper.toEntity(newUser)).willReturn(entityToSave);
            given(userJpaRepository.save(entityToSave)).willReturn(savedEntity);
            given(mapper.toDomain(savedEntity)).willReturn(savedUser);

            // when - save
            User saved = userPersistenceAdapter.save(newUser);

            // then - verify saved
            assertThat(saved.getId()).isEqualTo(10L);

            // given - findByEmail
            given(userJpaRepository.findByEmail("scenario@example.com")).willReturn(Optional.of(savedEntity));
            given(mapper.toDomain(savedEntity)).willReturn(savedUser);

            // when - find
            Optional<User> found = userPersistenceAdapter.findByEmail("scenario@example.com");

            // then - verify found
            assertThat(found).isPresent();
            assertThat(found.get().getId()).isEqualTo(10L);
            assertThat(found.get().getEmail()).isEqualTo("scenario@example.com");
        }

        @Test
        @DisplayName("이메일 중복 확인 시나리오")
        void checkEmailDuplication_Scenario() {
            // given
            String email = "duplicate@example.com";

            // when - 이메일이 존재하지 않음
            given(userJpaRepository.existsByEmail(email)).willReturn(false);
            boolean beforeSave = userPersistenceAdapter.existsByEmail(email);

            // then
            assertThat(beforeSave).isFalse();

            // when - 사용자 저장 후 이메일 존재
            given(userJpaRepository.existsByEmail(email)).willReturn(true);
            boolean afterSave = userPersistenceAdapter.existsByEmail(email);

            // then
            assertThat(afterSave).isTrue();
        }
    }

    @Nested
    @DisplayName("매퍼 통합 테스트")
    class MapperIntegrationTest {

        @Test
        @DisplayName("도메인 User를 Entity로 변환 후 저장한다")
        void save_ConvertsDomainToEntity() {
            // given
            User domainUser = createDomainUser(null, "mapper@example.com", "hash", UserRole.ADMIN);
            UserJpaEntity entity = createJpaEntity(null, "mapper@example.com", "hash", "ADMIN");
            UserJpaEntity savedEntity = createJpaEntity(1L, "mapper@example.com", "hash", "ADMIN");
            User savedDomain = createDomainUser(1L, "mapper@example.com", "hash", UserRole.ADMIN);

            given(mapper.toEntity(domainUser)).willReturn(entity);
            given(userJpaRepository.save(entity)).willReturn(savedEntity);
            given(mapper.toDomain(savedEntity)).willReturn(savedDomain);

            // when
            userPersistenceAdapter.save(domainUser);

            // then
            then(mapper).should().toEntity(domainUser);
        }

        @Test
        @DisplayName("Entity를 도메인 User로 변환하여 반환한다")
        void findById_ConvertsEntityToDomain() {
            // given
            Long userId = 1L;
            UserJpaEntity entity = createJpaEntity(userId, "test@example.com", "hash", "USER");
            User domainUser = createDomainUser(userId, "test@example.com", "hash", UserRole.USER);

            given(userJpaRepository.findById(userId)).willReturn(Optional.of(entity));
            given(mapper.toDomain(entity)).willReturn(domainUser);

            // when
            userPersistenceAdapter.findById(userId);

            // then
            then(mapper).should().toDomain(entity);
        }
    }

    // Helper methods
    private UserJpaEntity createJpaEntity(Long id, String email, String password, String role) {
        UserJpaEntity entity = new UserJpaEntity();
        entity.setId(id);
        entity.setEmail(email);
        entity.setPassword(password);
        entity.setRole(role);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    private User createDomainUser(Long id, String email, String passwordHash, UserRole role) {
        return new User(id, email, passwordHash, role, LocalDateTime.now(), LocalDateTime.now());
    }
}
