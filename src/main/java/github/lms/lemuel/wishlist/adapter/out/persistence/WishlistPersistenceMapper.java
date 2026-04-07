package github.lms.lemuel.wishlist.adapter.out.persistence;

import github.lms.lemuel.wishlist.domain.WishlistItem;

/**
 * 위시리스트 도메인 <-> JPA 엔티티 수동 매퍼
 */
public class WishlistPersistenceMapper {

    private WishlistPersistenceMapper() {}

    public static WishlistItemJpaEntity toEntity(WishlistItem domain) {
        WishlistItemJpaEntity entity = new WishlistItemJpaEntity();
        entity.setId(domain.getId());
        entity.setUserId(domain.getUserId());
        entity.setProductId(domain.getProductId());
        entity.setCreatedAt(domain.getCreatedAt());
        return entity;
    }

    public static WishlistItem toDomain(WishlistItemJpaEntity entity) {
        WishlistItem item = new WishlistItem();
        item.setId(entity.getId());
        item.setUserId(entity.getUserId());
        item.setProductId(entity.getProductId());
        item.setCreatedAt(entity.getCreatedAt());
        return item;
    }
}
