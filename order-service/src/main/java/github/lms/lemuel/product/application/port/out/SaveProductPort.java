package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.Product;

public interface SaveProductPort {
    Product save(Product product);
}
