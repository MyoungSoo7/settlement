package github.lms.lemuel.category.adapter.out.persistence;

import github.lms.lemuel.category.application.port.out.LoadEcommerceCategoryPort;
import github.lms.lemuel.category.application.port.out.SaveEcommerceCategoryPort;
import github.lms.lemuel.category.domain.EcommerceCategory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class EcommerceCategoryPersistenceAdapter
        implements LoadEcommerceCategoryPort, SaveEcommerceCategoryPort {

    private final SpringDataEcommerceCategoryRepository repository;
    private final EcommerceCategoryMapper mapper;

    public EcommerceCategoryPersistenceAdapter(SpringDataEcommerceCategoryRepository repository,
                                               EcommerceCategoryMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Optional<EcommerceCategory> findByIdNotDeleted(Long id) {
        return repository.findByIdNotDeleted(id).map(mapper::toDomainEntity);
    }

    @Override
    public Optional<EcommerceCategory> findBySlug(String slug) {
        return repository.findBySlug(slug).map(mapper::toDomainEntity);
    }

    @Override
    public List<EcommerceCategory> findAllNotDeleted() {
        return repository.findAllNotDeleted().stream()
                .map(mapper::toDomainEntity).collect(Collectors.toList());
    }

    @Override
    public List<EcommerceCategory> findAllActiveNotDeleted() {
        return repository.findAllActiveNotDeleted().stream()
                .map(mapper::toDomainEntity).collect(Collectors.toList());
    }

    @Override
    public List<EcommerceCategory> findByParentId(Long parentId) {
        return repository.findByParentId(parentId).stream()
                .map(mapper::toDomainEntity).collect(Collectors.toList());
    }

    @Override
    public long countChildrenByParentId(Long parentId) {
        return repository.countChildrenByParentId(parentId);
    }

    @Override
    public boolean hasProducts(Long categoryId) {
        return repository.hasProducts(categoryId);
    }

    @Override
    public EcommerceCategory save(EcommerceCategory category) {
        EcommerceCategoryJpaEntity saved = repository.save(mapper.toJpaEntity(category));
        return mapper.toDomainEntity(saved);
    }
}
