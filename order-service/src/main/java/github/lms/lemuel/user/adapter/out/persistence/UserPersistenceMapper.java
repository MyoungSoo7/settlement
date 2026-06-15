package github.lms.lemuel.user.adapter.out.persistence;

import github.lms.lemuel.user.domain.MembershipStatus;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Domain <-> JpaEntity 매핑 (MapStruct)
 */
@Mapper(componentModel = "spring", imports = {UserRole.class, MembershipStatus.class})
public interface UserPersistenceMapper {

    @Mapping(target = "passwordHash", source = "password")
    @Mapping(target = "role", expression = "java(UserRole.fromString(entity.getRole()))")
    @Mapping(target = "active", expression = "java(entity.getActive() == null || entity.getActive())")
    @Mapping(target = "membershipStatus", expression = "java(MembershipStatus.fromString(entity.getMembershipStatus()))")
    User toDomain(UserJpaEntity entity);

    @Mapping(target = "password", source = "passwordHash")
    @Mapping(target = "role", expression = "java(domain.getRole().name())")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "membershipStatus", expression = "java(domain.getMembershipStatus() == null ? \"APPROVED\" : domain.getMembershipStatus().name())")
    UserJpaEntity toEntity(User domain);
}
