package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.SaveProductPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Comparator;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ProductPersistenceAdapter implements LoadProductPort, SaveProductPort {

    private final SpringDataProductJpaRepository repository;
    private final ProductPersistenceMapper mapper;

    @Override
    public Product save(Product product) {
        ProductJpaEntity entity = mapper.toEntity(product);
        ProductJpaEntity savedEntity = repository.save(entity);
        return mapper.toDomain(savedEntity);
    }

    @Override
    public Optional<Product> findById(Long productId) {
        return repository.findById(productId)
                .map(mapper::toDomain);
    }

    @Override
    public Optional<Product> findByName(String name) {
        return repository.findByName(name)
                .map(mapper::toDomain);
    }

    @Override
    public List<Product> findAll() {
        return repository.findAll().stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<Product> findByStatus(ProductStatus status) {
        return repository.findByStatus(status).stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<Product> findAvailableProducts() {
        return repository.findAvailableProducts().stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<Product> search(String keyword, Long categoryId, String sortBy, String sortDirection) {
        Comparator<Product> comparator = comparator(sortBy);
        if ("DESC".equalsIgnoreCase(sortDirection)) {
            comparator = comparator.reversed();
        }
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return repository.search(normalizedKeyword, categoryId).stream()
                .map(mapper::toDomain)
                .sorted(comparator)
                .collect(Collectors.toList());
    }

    @Override
    public int decreaseStockIfAvailable(Long productId, int quantity) {
        return repository.decreaseStockIfAvailable(productId, quantity, LocalDateTime.now());
    }

    @Override
    public int increaseStock(Long productId, int quantity) {
        return repository.increaseStock(productId, quantity, LocalDateTime.now());
    }

    @Override
    public boolean existsByName(String name) {
        return repository.existsByName(name);
    }

    private static Comparator<Product> comparator(String sortBy) {
        return switch (sortBy == null ? "" : sortBy) {
            case "price" -> Comparator.comparing(Product::getPrice);
            case "latest", "createdAt" -> Comparator.comparing(Product::getCreatedAt);
            case "name" -> Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparing(Product::getId);
        };
    }
}
