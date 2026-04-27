package github.lms.lemuel.user.adapter.out.persistence;

import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Domain <-> JpaEntity 매핑 (MapStruct)
 */
@Mapper(componentModel = "spring", imports = UserRole.class)
public interface UserPersistenceMapper {

    @Mapping(target = "passwordHash", source = "password")
    @Mapping(target = "role", expression = "java(UserRole.fromString(entity.getRole()))")
    User toDomain(UserJpaEntity entity);

    @Mapping(target = "password", source = "passwordHash")
    @Mapping(target = "role", expression = "java(domain.getRole().name())")
    UserJpaEntity toEntity(User domain);
}
