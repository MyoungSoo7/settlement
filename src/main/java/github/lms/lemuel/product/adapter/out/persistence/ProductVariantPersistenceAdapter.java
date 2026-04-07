package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.application.port.out.SaveProductVariantPort;
import github.lms.lemuel.product.domain.ProductOption;
import github.lms.lemuel.product.domain.ProductOptionValue;
import github.lms.lemuel.product.domain.ProductVariant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ProductVariantPersistenceAdapter implements LoadProductVariantPort, SaveProductVariantPort {

    private final SpringDataProductOptionRepository optionRepository;
    private final SpringDataProductOptionValueRepository optionValueRepository;
    private final SpringDataProductVariantRepository variantRepository;
    private final ProductVariantPersistenceMapper mapper;

    @Override
    public List<ProductOption> findOptionsByProductId(Long productId) {
        return optionRepository.findByProductIdOrderBySortOrder(productId).stream()
                .map(optionEntity -> {
                    List<ProductOptionValue> values = optionValueRepository
                            .findByOptionIdOrderBySortOrder(optionEntity.getId()).stream()
                            .map(mapper::toOptionValueDomain)
                            .collect(Collectors.toList());
                    return mapper.toOptionDomain(optionEntity, values);
                })
                .collect(Collectors.toList());
    }

    @Override
    public Optional<ProductOption> findOptionById(Long optionId) {
        return optionRepository.findById(optionId)
                .map(optionEntity -> {
                    List<ProductOptionValue> values = optionValueRepository
                            .findByOptionIdOrderBySortOrder(optionEntity.getId()).stream()
                            .map(mapper::toOptionValueDomain)
                            .collect(Collectors.toList());
                    return mapper.toOptionDomain(optionEntity, values);
                });
    }

    @Override
    public List<ProductVariant> findVariantsByProductId(Long productId) {
        return variantRepository.findByProductId(productId).stream()
                .map(mapper::toVariantDomain)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<ProductVariant> findVariantById(Long variantId) {
        return variantRepository.findById(variantId)
                .map(mapper::toVariantDomain);
    }

    @Override
    public Optional<ProductVariant> findVariantBySku(String sku) {
        return variantRepository.findBySku(sku)
                .map(mapper::toVariantDomain);
    }

    @Override
    public ProductOption saveOption(ProductOption option) {
        ProductOptionJpaEntity jpaEntity = mapper.toOptionJpaEntity(option);
        ProductOptionJpaEntity saved = optionRepository.save(jpaEntity);
        List<ProductOptionValue> values = optionValueRepository
                .findByOptionIdOrderBySortOrder(saved.getId()).stream()
                .map(mapper::toOptionValueDomain)
                .collect(Collectors.toList());
        return mapper.toOptionDomain(saved, values);
    }

    @Override
    public ProductOptionValue saveOptionValue(ProductOptionValue optionValue) {
        ProductOptionValueJpaEntity jpaEntity = mapper.toOptionValueJpaEntity(optionValue);
        ProductOptionValueJpaEntity saved = optionValueRepository.save(jpaEntity);
        return mapper.toOptionValueDomain(saved);
    }

    @Override
    public ProductVariant saveVariant(ProductVariant variant) {
        ProductVariantJpaEntity jpaEntity = mapper.toVariantJpaEntity(variant);
        ProductVariantJpaEntity saved = variantRepository.save(jpaEntity);
        return mapper.toVariantDomain(saved);
    }

    @Override
    @Transactional
    public void deleteOption(Long optionId) {
        optionValueRepository.deleteByOptionId(optionId);
        optionRepository.deleteById(optionId);
    }

    @Override
    public void deleteOptionValue(Long optionValueId) {
        optionValueRepository.deleteById(optionValueId);
    }
}
