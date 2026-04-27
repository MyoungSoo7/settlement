package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.UpdateProductUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.SaveProductPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.exception.InsufficientStockException;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UpdateProductService implements UpdateProductUseCase {

    private final LoadProductPort loadProductPort;
    private final SaveProductPort saveProductPort;

    @Override
    @CacheEvict(value = "products", allEntries = true)
    public Product updateProductInfo(UpdateProductInfoCommand command) {
        log.info("상품 정보 수정: productId={}", command.productId());

        Product product = loadProductPort.findById(command.productId())
                .orElseThrow(() -> new ProductNotFoundException(command.productId()));

        product.updateInfo(command.name(), command.description());
        Product updatedProduct = saveProductPort.save(product);

        log.info("상품 정보 수정 완료: productId={}", updatedProduct.getId());
        return updatedProduct;
    }

    @Override
    @CacheEvict(value = "products", allEntries = true)
    public Product updateProductPrice(UpdateProductPriceCommand command) {
        log.info("상품 가격 수정: productId={}, newPrice={}", command.productId(), command.newPrice());

        Product product = loadProductPort.findById(command.productId())
                .orElseThrow(() -> new ProductNotFoundException(command.productId()));

        product.changePrice(command.newPrice());
        Product updatedProduct = saveProductPort.save(product);

        log.info("상품 가격 수정 완료: productId={}, newPrice={}",
                updatedProduct.getId(), updatedProduct.getPrice());
        return updatedProduct;
    }

    @Override
    @CacheEvict(value = "products", allEntries = true)
    public Product updateProductStock(UpdateProductStockCommand command) {
        log.info("상품 재고 수정: productId={}, quantity={}, operation={}",
                command.productId(), command.quantity(), command.operation());

        Product product = loadProductPort.findById(command.productId())
                .orElseThrow(() -> new ProductNotFoundException(command.productId()));

        try {
            if (command.operation() == StockOperation.INCREASE) {
                product.increaseStock(command.quantity());
            } else {
                product.decreaseStock(command.quantity());
            }
        } catch (IllegalStateException e) {
            // 재고 부족 예외를 도메인 예외로 변환
            throw new InsufficientStockException(
                    product.getId(),
                    command.quantity(),
                    product.getStockQuantity()
            );
        }

        Product updatedProduct = saveProductPort.save(product);

        log.info("상품 재고 수정 완료: productId={}, stock={}",
                updatedProduct.getId(), updatedProduct.getStockQuantity());
        return updatedProduct;
    }
}
