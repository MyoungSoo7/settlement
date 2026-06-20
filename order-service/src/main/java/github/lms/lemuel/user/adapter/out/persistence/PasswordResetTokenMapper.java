package github.lms.lemuel.user.adapter.out.persistence;

import github.lms.lemuel.user.domain.PasswordResetToken;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface PasswordResetTokenMapper {

    PasswordResetToken toDomain(PasswordResetTokenJpaEntity entity);

    PasswordResetTokenJpaEntity toEntity(PasswordResetToken domain);
}
