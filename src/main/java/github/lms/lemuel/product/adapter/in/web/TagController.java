package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.product.adapter.in.web.request.CreateTagRequest;
import github.lms.lemuel.product.adapter.in.web.request.UpdateTagRequest;
import github.lms.lemuel.product.adapter.in.web.response.TagResponse;
import github.lms.lemuel.product.application.port.in.TagUseCase;
import github.lms.lemuel.product.domain.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagUseCase tagUseCase;

    @PostMapping
    public ResponseEntity<TagResponse> createTag(@RequestBody CreateTagRequest request) {
        Tag tag = tagUseCase.createTag(request.getName(), request.getColor());
        return ResponseEntity.status(HttpStatus.CREATED).body(TagResponse.from(tag));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TagResponse> getTag(@PathVariable Long id) {
        Tag tag = tagUseCase.getTagById(id);
        return ResponseEntity.ok(TagResponse.from(tag));
    }

    @GetMapping
    public ResponseEntity<List<TagResponse>> getAllTags() {
        List<Tag> tags = tagUseCase.getAllTags();
        return ResponseEntity.ok(tags.stream()
                .map(TagResponse::from)
                .collect(Collectors.toList()));
    }

    @GetMapping("/product/{productId}")
    public ResponseEntity<List<TagResponse>> getTagsByProduct(@PathVariable Long productId) {
        List<Tag> tags = tagUseCase.getTagsByProductId(productId);
        return ResponseEntity.ok(tags.stream()
                .map(TagResponse::from)
                .collect(Collectors.toList()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TagResponse> updateTag(
            @PathVariable Long id,
            @RequestBody UpdateTagRequest request) {
        Tag tag = tagUseCase.updateTag(id, request.getName(), request.getColor());
        return ResponseEntity.ok(TagResponse.from(tag));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTag(@PathVariable Long id) {
        tagUseCase.deleteTag(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/product/{productId}/tag/{tagId}")
    public ResponseEntity<Void> addTagToProduct(
            @PathVariable Long productId,
            @PathVariable Long tagId) {
        tagUseCase.addTagToProduct(productId, tagId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/product/{productId}/tag/{tagId}")
    public ResponseEntity<Void> removeTagFromProduct(
            @PathVariable Long productId,
            @PathVariable Long tagId) {
        tagUseCase.removeTagFromProduct(productId, tagId);
        return ResponseEntity.ok().build();
    }
}
