package github.lms.lemuel.user.adapter.out.persistence;

import github.lms.lemuel.user.domain.SocialAccount;
import github.lms.lemuel.user.domain.SocialProvider;
import org.springframework.stereotype.Component;

/**
 * SocialAccount Domain <-> JpaEntity 매핑 (수동 매퍼)
 */
@Component
public class SocialAccountPersistenceMapper {

    public SocialAccount toDomain(SocialAccountJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return new SocialAccount(
                entity.getId(),
                entity.getUserId(),
                SocialProvider.fromString(entity.getProvider()),
                entity.getProviderId(),
                entity.getEmail(),
                entity.getName(),
                entity.getProfileImage(),
                entity.getAccessToken(),
                entity.getRefreshToken(),
                entity.getTokenExpiresAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public SocialAccountJpaEntity toEntity(SocialAccount domain) {
        if (domain == null) {
            return null;
        }
        return new SocialAccountJpaEntity(
                domain.getId(),
                domain.getUserId(),
                domain.getProvider().name(),
                domain.getProviderId(),
                domain.getEmail(),
                domain.getName(),
                domain.getProfileImage(),
                domain.getAccessToken(),
                domain.getRefreshToken(),
                domain.getTokenExpiresAt(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }
}
