package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.out.LoadProductImagePort;
import github.lms.lemuel.product.application.port.out.SaveProductImagePort;
import github.lms.lemuel.product.domain.ProductImage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductImageService {

    private final LoadProductImagePort loadPort;
    private final SaveProductImagePort savePort;
    private final FileStorageService fileStorageService;

    @Transactional
    public List<ProductImage> uploadImages(Long productId, List<MultipartFile> files) {
        List<ProductImage> images = new ArrayList<>();

        long currentCount = loadPort.countByProductIdNotDeleted(productId);
        int orderIndex = (int) currentCount;

        for (MultipartFile file : files) {
            if (!fileStorageService.isValidImageType(file.getContentType())) {
                throw new IllegalArgumentException("Invalid image type: " + file.getContentType());
            }
            if (!fileStorageService.isValidFileSize(file.getSize())) {
                throw new IllegalArgumentException("File size exceeds 5MB limit");
            }

            try {
                FileStorageService.StoredFileInfo fileInfo = fileStorageService.store(file, productId);

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

                images.add(savePort.save(image));

            } catch (IOException e) {
                throw new RuntimeException("Failed to upload image: " + file.getOriginalFilename(), e);
            }
        }

        if (currentCount == 0 && !images.isEmpty()) {
            setPrimaryImage(productId, images.get(0).getId());
        }

        return images;
    }

    @Transactional
    public ProductImage setPrimaryImage(Long productId, Long imageId) {
        ProductImage image = getImageById(imageId);

        if (!image.getProductId().equals(productId)) {
            throw new IllegalArgumentException("Image does not belong to product");
        }

        loadPort.findPrimaryImageByProductId(productId).ifPresent(current -> {
            current.unmarkAsPrimary();
            savePort.save(current);
        });

        image.markAsPrimary();
        return savePort.save(image);
    }

    @Transactional
    public List<ProductImage> reorderImages(Long productId, List<Long> imageIds) {
        List<ProductImage> images = new ArrayList<>();

        for (int i = 0; i < imageIds.size(); i++) {
            Long imageId = imageIds.get(i);
            ProductImage image = getImageById(imageId);

            if (!image.getProductId().equals(productId)) {
                throw new IllegalArgumentException("Image does not belong to product");
            }

            image.changeOrder(i);
            images.add(savePort.save(image));
        }

        return images;
    }

    @Transactional
    public void deleteImage(Long productId, Long imageId) {
        ProductImage image = getImageById(imageId);

        if (!image.getProductId().equals(productId)) {
            throw new IllegalArgumentException("Image does not belong to product");
        }

        boolean wasPrimary = image.getIsPrimary();

        image.softDelete();
        savePort.save(image);

        fileStorageService.delete(image.getFilePath());

        if (wasPrimary) {
            List<ProductImage> remaining = loadPort.findByProductIdNotDeleted(productId);
            if (!remaining.isEmpty()) {
                ProductImage newPrimary = remaining.get(0);
                newPrimary.markAsPrimary();
                savePort.save(newPrimary);
            }
        }
    }

    public List<ProductImage> getProductImages(Long productId) {
        return loadPort.findByProductIdNotDeleted(productId);
    }

    public ProductImage getPrimaryImage(Long productId) {
        return loadPort.findPrimaryImageByProductId(productId).orElse(null);
    }

    public String getPrimaryImageUrl(Long productId) {
        ProductImage primary = getPrimaryImage(productId);
        return primary != null ? primary.getUrl() : null;
    }

    private ProductImage getImageById(Long imageId) {
        return loadPort.findByIdNotDeleted(imageId)
                .orElseThrow(() -> new IllegalArgumentException("Image not found: " + imageId));
    }
}
