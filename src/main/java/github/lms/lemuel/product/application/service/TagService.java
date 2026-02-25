package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.TagUseCase;
import github.lms.lemuel.product.application.port.out.LoadTagPort;
import github.lms.lemuel.product.application.port.out.SaveTagPort;
import github.lms.lemuel.product.domain.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TagService implements TagUseCase {

    private final SaveTagPort saveTagPort;
    private final LoadTagPort loadTagPort;

    @Override
    @Transactional
    public Tag createTag(String name, String color) {
        Tag tag = Tag.create(name, color);
        return saveTagPort.save(tag);
    }

    @Override
    public Tag getTagById(Long id) {
        return loadTagPort.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Tag not found: " + id));
    }

    @Override
    public List<Tag> getAllTags() {
        return loadTagPort.findAll();
    }

    @Override
    public List<Tag> getTagsByProductId(Long productId) {
        return loadTagPort.findByProductId(productId);
    }

    @Override
    @Transactional
    public Tag updateTag(Long id, String name, String color) {
        Tag tag = getTagById(id);
        tag.updateInfo(name, color);
        return saveTagPort.save(tag);
    }

    @Override
    @Transactional
    public void deleteTag(Long id) {
        saveTagPort.delete(id);
    }

    @Override
    @Transactional
    public void addTagToProduct(Long productId, Long tagId) {
        // 태그 존재 여부 확인
        getTagById(tagId);
        saveTagPort.addTagToProduct(productId, tagId);
    }

    @Override
    @Transactional
    public void removeTagFromProduct(Long productId, Long tagId) {
        saveTagPort.removeTagFromProduct(productId, tagId);
    }
}
