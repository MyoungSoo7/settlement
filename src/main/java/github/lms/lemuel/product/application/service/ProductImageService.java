package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.adapter.out.persistence.ProductImageJpaEntity;
import github.lms.lemuel.product.adapter.out.persistence.ProductImageMapper;
import github.lms.lemuel.product.adapter.out.persistence.SpringDataProductImageRepository;
import github.lms.lemuel.product.domain.ProductImage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductImageService {

    private final SpringDataProductImageRepository repository;
    private final ProductImageMapper mapper;
    private final FileStorageService fileStorageService;

    /**
     * 이미지 업로드
     */
    @Transactional
    public List<ProductImage> uploadImages(Long productId, List<MultipartFile> files) {
        List<ProductImage> images = new java.util.ArrayList<>();

        // 현재 이미지 개수 조회
        long currentCount = repository.countByProductIdNotDeleted(productId);
        int orderIndex = (int) currentCount;

        for (MultipartFile file : files) {
            // 파일 검증
            if (!fileStorageService.isValidImageType(file.getContentType())) {
                throw new IllegalArgumentException("Invalid image type: " + file.getContentType());
            }
            if (!fileStorageService.isValidFileSize(file.getSize())) {
                throw new IllegalArgumentException("File size exceeds 5MB limit");
            }

            try {
                // 파일 저장
                FileStorageService.StoredFileInfo fileInfo = fileStorageService.store(file, productId);

                // 도메인 객체 생성
                ProductImage image = ProductImage.create(
                        productId,
                        file.getOriginalFilename(),
                        fileInfo.getStoredFileName(),
                        fileInfo.getFilePath(),
                        fileInfo.getUrl(),
                        file.getContentType(),
                        file.getSize(),
                        fileInfo.getWidth(),
                        fileInfo.getHeight(),
                        orderIndex++
                );
                image.setChecksum(fileInfo.getChecksum());

                // DB 저장
                ProductImageJpaEntity saved = repository.save(mapper.toJpaEntity(image));
                images.add(mapper.toDomainEntity(saved));

            } catch (IOException e) {
                throw new RuntimeException("Failed to upload image: " + file.getOriginalFilename(), e);
            }
        }

        // 첫 번째 이미지가 추가되고 대표 이미지가 없으면 자동 지정
        if (currentCount == 0 && !images.isEmpty()) {
            setPrimaryImage(productId, images.get(0).getId());
        }

        return images;
    }

    /**
     * 대표 이미지 지정
     */
    @Transactional
    public ProductImage setPrimaryImage(Long productId, Long imageId) {
        ProductImage image = getImageById(imageId);

        if (!image.getProductId().equals(productId)) {
            throw new IllegalArgumentException("Image does not belong to product");
        }

        // 기존 대표 이미지 해제
        repository.findPrimaryImageByProductId(productId).ifPresent(current -> {
            ProductImage currentPrimary = mapper.toDomainEntity(current);
            currentPrimary.unmarkAsPrimary();
            repository.save(mapper.toJpaEntity(currentPrimary));
        });

        // 새 대표 이미지 지정
        image.markAsPrimary();
        ProductImageJpaEntity updated = repository.save(mapper.toJpaEntity(image));
        return mapper.toDomainEntity(updated);
    }

    /**
     * 이미지 순서 변경
     */
    @Transactional
    public List<ProductImage> reorderImages(Long productId, List<Long> imageIds) {
        List<ProductImage> images = new java.util.ArrayList<>();

        for (int i = 0; i < imageIds.size(); i++) {
            Long imageId = imageIds.get(i);
            ProductImage image = getImageById(imageId);

            if (!image.getProductId().equals(productId)) {
                throw new IllegalArgumentException("Image does not belong to product");
            }

            image.changeOrder(i);
            ProductImageJpaEntity updated = repository.save(mapper.toJpaEntity(image));
            images.add(mapper.toDomainEntity(updated));
        }

        return images;
    }

    /**
     * 이미지 삭제 (soft delete)
     */
    @Transactional
    public void deleteImage(Long productId, Long imageId) {
        ProductImage image = getImageById(imageId);

        if (!image.getProductId().equals(productId)) {
            throw new IllegalArgumentException("Image does not belong to product");
        }

        boolean wasPrimary = image.getIsPrimary();

        // Soft delete
        image.softDelete();
        repository.save(mapper.toJpaEntity(image));

        // 파일 삭제 (비동기로 처리 가능)
        fileStorageService.delete(image.getFilePath());

        // 대표 이미지였다면 다른 이미지를 대표로 지정
        if (wasPrimary) {
            List<ProductImageJpaEntity> remainingImages = repository.findByProductIdNotDeleted(productId);
            if (!remainingImages.isEmpty()) {
                ProductImage newPrimary = mapper.toDomainEntity(remainingImages.get(0));
                newPrimary.markAsPrimary();
                repository.save(mapper.toJpaEntity(newPrimary));
            }
        }
    }

    /**
     * 상품의 이미지 목록 조회
     */
    public List<ProductImage> getProductImages(Long productId) {
        return repository.findByProductIdNotDeleted(productId).stream()
                .map(mapper::toDomainEntity)
                .collect(Collectors.toList());
    }

    /**
     * 대표 이미지 조회
     */
    public ProductImage getPrimaryImage(Long productId) {
        return repository.findPrimaryImageByProductId(productId)
                .map(mapper::toDomainEntity)
                .orElse(null);
    }

    /**
     * 대표 이미지 URL 조회
     */
    public String getPrimaryImageUrl(Long productId) {
        ProductImage primaryImage = getPrimaryImage(productId);
        return primaryImage != null ? primaryImage.getUrl() : null;
    }

    /**
     * 이미지 단건 조회
     */
    private ProductImage getImageById(Long imageId) {
        return repository.findByIdNotDeleted(imageId)
                .map(mapper::toDomainEntity)
                .orElseThrow(() -> new IllegalArgumentException("Image not found: " + imageId));
    }
}
