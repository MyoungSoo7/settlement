package github.lms.lemuel.cart.adapter.out.persistence;

import github.lms.lemuel.cart.domain.Cart;
import github.lms.lemuel.cart.domain.CartItem;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class CartPersistenceMapper {

    public CartJpaEntity toJpaEntity(Cart cart) {
        if (cart == null) {
            return null;
        }
        return new CartJpaEntity(
                cart.getId(),
                cart.getUserId(),
                cart.getStatus(),
                cart.getCreatedAt(),
                cart.getUpdatedAt()
        );
    }

    public Cart toDomainEntity(CartJpaEntity jpaEntity, List<CartItemJpaEntity> itemEntities) {
        if (jpaEntity == null) {
            return null;
        }
        List<CartItem> items = itemEntities != null
                ? itemEntities.stream().map(this::toDomainItem).collect(Collectors.toList())
                : List.of();

        return new Cart(
                jpaEntity.getId(),
                jpaEntity.getUserId(),
                jpaEntity.getStatus(),
                items,
                jpaEntity.getCreatedAt(),
                jpaEntity.getUpdatedAt()
        );
    }

    public Cart toDomainEntity(CartJpaEntity jpaEntity) {
        return toDomainEntity(jpaEntity, List.of());
    }

    public CartItemJpaEntity toJpaItemEntity(CartItem item) {
        if (item == null) {
            return null;
        }
        return new CartItemJpaEntity(
                item.getId(),
                item.getCartId(),
                item.getProductId(),
                item.getQuantity(),
                item.getPriceSnapshot(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }

    public CartItem toDomainItem(CartItemJpaEntity jpaEntity) {
        if (jpaEntity == null) {
            return null;
        }
        return new CartItem(
                jpaEntity.getId(),
                jpaEntity.getCartId(),
                jpaEntity.getProductId(),
                jpaEntity.getQuantity(),
                jpaEntity.getPriceSnapshot(),
                jpaEntity.getCreatedAt(),
                jpaEntity.getUpdatedAt()
        );
    }
}
