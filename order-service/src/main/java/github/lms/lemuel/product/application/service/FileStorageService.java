package github.lms.lemuel.product.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

@Service
public class FileStorageService {

    @Value("${app.upload.dir:/data/uploads}")
    private String uploadDir;

    @Value("${app.upload.base-url:/assets}")
    private String baseUrl;

    /**
     * 파일 저장 결과
     */
    public static class StoredFileInfo {
        private final String storedFileName;
        private final String filePath;
        private final String url;
        private final Integer width;
        private final Integer height;
        private final String checksum;

        public StoredFileInfo(String storedFileName, String filePath, String url,
                               Integer width, Integer height, String checksum) {
            this.storedFileName = storedFileName;
            this.filePath = filePath;
            this.url = url;
            this.width = width;
            this.height = height;
            this.checksum = checksum;
        }

        public String getStoredFileName() { return storedFileName; }
        public String getFilePath() { return filePath; }
        public String getUrl() { return url; }
        public Integer getWidth() { return width; }
        public Integer getHeight() { return height; }
        public String getChecksum() { return checksum; }
    }

    /**
     * 파일 저장 (로컬 파일시스템)
     */
    public StoredFileInfo store(MultipartFile file, Long productId) throws IOException {
        // 디렉토리 생성
        Path productDir = Paths.get(uploadDir, "products", productId.toString());
        Files.createDirectories(productDir);

        // UUID 기반 파일명 생성 (path traversal 방지)
        String extension = getFileExtension(file.getOriginalFilename());
        String storedFileName = UUID.randomUUID().toString() + extension;
        Path targetPath = productDir.resolve(storedFileName);

        // 파일 저장
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }

        // 이미지 크기 추출
        Integer width = null;
        Integer height = null;
        try {
            BufferedImage image = ImageIO.read(targetPath.toFile());
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }
        } catch (Exception e) {
            // 이미지 읽기 실패 시 null 유지
        }

        // Checksum 계산 (선택적)
        String checksum = calculateChecksum(targetPath);

        // URL 생성
        String url = String.format("%s/products/%d/%s", baseUrl, productId, storedFileName);

        return new StoredFileInfo(storedFileName, targetPath.toString(), url, width, height, checksum);
    }

    /**
     * 파일 삭제
     */
    public void delete(String filePath) {
        try {
            Path path = Paths.get(filePath);
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // 로그 기록 (실패해도 DB에서는 삭제됨)
            System.err.println("Failed to delete file: " + filePath);
        }
    }

    /**
     * 파일 확장자 추출
     */
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf("."));
    }

    /**
     * SHA-256 Checksum 계산
     */
    private String calculateChecksum(Path filePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] fileBytes = Files.readAllBytes(filePath);
            byte[] hashBytes = digest.digest(fileBytes);

            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            return null;
        }
    }

    /**
     * 파일 타입 검증
     */
    public boolean isValidImageType(String contentType) {
        return contentType != null &&
                (contentType.equals("image/jpeg") ||
                 contentType.equals("image/jpg") ||
                 contentType.equals("image/png") ||
                 contentType.equals("image/webp"));
    }

    /**
     * 파일 크기 검증 (5MB)
     */
    public boolean isValidFileSize(long sizeBytes) {
        long maxSize = 5 * 1024 * 1024; // 5MB
        return sizeBytes > 0 && sizeBytes <= maxSize;
    }
}
