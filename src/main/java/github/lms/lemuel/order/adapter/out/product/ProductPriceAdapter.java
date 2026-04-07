package github.lms.lemuel.order.adapter.out.product;

import github.lms.lemuel.order.application.port.out.LoadProductForOrderPort;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.domain.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Order에서 Product 가격을 조회하는 Adapter
 * Product 모듈의 Port를 사용하여 의존성 분리
 */
@Component
@RequiredArgsConstructor
public class ProductPriceAdapter implements LoadProductForOrderPort {

    private final LoadProductPort loadProductPort;

    @Override
    public Optional<BigDecimal> findPriceById(Long productId) {
        return loadProductPort.findById(productId)
                .map(Product::getPrice);
    }

    @Override
    public boolean existsById(Long productId) {
        return loadProductPort.findById(productId).isPresent();
    }
}
