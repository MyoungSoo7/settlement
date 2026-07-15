package github.lms.lemuel.user.adapter.out.persistence;

import github.lms.lemuel.user.domain.MembershipStatus;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import org.springframework.stereotype.Component;

/**
 * Domain <-> JpaEntity 수동 매핑.
 *
 * <p>User 도메인이 불변식을 강제하는 봉인 객체(@Setter 없음)라 MapStruct 대신 수동 매핑을 유지한다
 * (PaymentMapper 와 동형). 복원은 {@link User#rehydrate} 로 DB 값(membershipStatus 포함)을 그대로 재구성한다.
 */
@Component
public class UserPersistenceMapper {

    public User toDomain(UserJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        boolean active = entity.getActive() == null || entity.getActive();
        return User.rehydrate(
                entity.getId(),
                entity.getEmail(),
                entity.getPassword(),
                UserRole.fromString(entity.getRole()),
                entity.getName(),
                entity.getPhoneNumber(),
                active,
                MembershipStatus.fromString(entity.getMembershipStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public UserJpaEntity toEntity(User domain) {
        if (domain == null) {
            return null;
        }
        UserJpaEntity entity = new UserJpaEntity();
        entity.setId(domain.getId());
        entity.setEmail(domain.getEmail());
        entity.setPassword(domain.getPasswordHash());
        entity.setRole(domain.getRole().name());
        entity.setName(domain.getName());
        entity.setPhoneNumber(domain.getPhoneNumber());
        entity.setActive(domain.isActive());
        entity.setMembershipStatus(
                domain.getMembershipStatus() == null ? "APPROVED" : domain.getMembershipStatus().name());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }
}
