package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.application.port.out.LoadCategoryPort;
import github.lms.lemuel.product.application.port.out.SaveCategoryPort;
import github.lms.lemuel.product.domain.Category;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CategoryPersistenceAdapter implements SaveCategoryPort, LoadCategoryPort {

    private final SpringDataCategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;

    @Override
    public Category save(Category category) {
        CategoryJpaEntity jpaEntity = categoryMapper.toJpaEntity(category);
        CategoryJpaEntity saved = categoryRepository.save(jpaEntity);
        return categoryMapper.toDomainEntity(saved);
    }

    @Override
    public Optional<Category> findById(Long id) {
        return categoryRepository.findById(id)
                .map(categoryMapper::toDomainEntity);
    }

    @Override
    public Optional<Category> findByName(String name) {
        return categoryRepository.findByName(name)
                .map(categoryMapper::toDomainEntity);
    }

    @Override
    public List<Category> findAll() {
        return categoryRepository.findAllByOrderByDisplayOrderAsc().stream()
                .map(categoryMapper::toDomainEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<Category> findActiveCategories() {
        return categoryRepository.findByIsActiveTrueOrderByDisplayOrderAsc().stream()
                .map(categoryMapper::toDomainEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<Category> findRootCategories() {
        return categoryRepository.findByParentIdIsNull().stream()
                .map(categoryMapper::toDomainEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<Category> findSubCategories(Long parentId) {
        return categoryRepository.findByParentId(parentId).stream()
                .map(categoryMapper::toDomainEntity)
                .collect(Collectors.toList());
    }
}
