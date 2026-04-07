package github.lms.lemuel.cart.adapter.out.persistence;

import github.lms.lemuel.cart.application.port.out.LoadCartPort;
import github.lms.lemuel.cart.application.port.out.SaveCartPort;
import github.lms.lemuel.cart.domain.Cart;
import github.lms.lemuel.cart.domain.CartItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CartPersistenceAdapter implements LoadCartPort, SaveCartPort {

    private final SpringDataCartJpaRepository cartRepository;
    private final SpringDataCartItemJpaRepository cartItemRepository;
    private final CartPersistenceMapper mapper;

    @Override
    public Optional<Cart> findActiveByUserId(Long userId) {
        return cartRepository.findActiveByUserId(userId)
                .map(cartEntity -> {
                    List<CartItemJpaEntity> itemEntities = cartItemRepository.findByCartId(cartEntity.getId());
                    return mapper.toDomainEntity(cartEntity, itemEntities);
                });
    }

    @Override
    public Optional<Cart> findById(Long id) {
        return cartRepository.findById(id)
                .map(cartEntity -> {
                    List<CartItemJpaEntity> itemEntities = cartItemRepository.findByCartId(cartEntity.getId());
                    return mapper.toDomainEntity(cartEntity, itemEntities);
                });
    }

    @Override
    public Cart save(Cart cart) {
        CartJpaEntity cartEntity = mapper.toJpaEntity(cart);
        CartJpaEntity savedEntity = cartRepository.save(cartEntity);
        List<CartItemJpaEntity> itemEntities = cartItemRepository.findByCartId(savedEntity.getId());
        return mapper.toDomainEntity(savedEntity, itemEntities);
    }

    @Override
    public CartItem saveItem(CartItem item) {
        CartItemJpaEntity itemEntity = mapper.toJpaItemEntity(item);
        CartItemJpaEntity savedEntity = cartItemRepository.save(itemEntity);
        return mapper.toDomainItem(savedEntity);
    }

    @Override
    public void deleteItem(Long itemId) {
        cartItemRepository.deleteById(itemId);
    }

    @Override
    public void deleteItemByCartIdAndProductId(Long cartId, Long productId) {
        cartItemRepository.deleteByCartIdAndProductId(cartId, productId);
    }

    @Override
    public void deleteAllItemsByCartId(Long cartId) {
        cartItemRepository.deleteByCartId(cartId);
    }
}
