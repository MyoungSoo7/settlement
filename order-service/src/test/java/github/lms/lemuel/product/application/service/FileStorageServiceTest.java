package github.lms.lemuel.product.application.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class FileStorageServiceTest {

    private final FileStorageService service = new FileStorageService();

    @Test @DisplayName("isValidImageType: jpeg 허용")
    void validImageType_jpeg() {
        assertThat(service.isValidImageType("image/jpeg")).isTrue();
    }

    @Test @DisplayName("isValidImageType: jpg 허용")
    void validImageType_jpg() {
        assertThat(service.isValidImageType("image/jpg")).isTrue();
    }

    @Test @DisplayName("isValidImageType: png 허용")
    void validImageType_png() {
        assertThat(service.isValidImageType("image/png")).isTrue();
    }

    @Test @DisplayName("isValidImageType: webp 허용")
    void validImageType_webp() {
        assertThat(service.isValidImageType("image/webp")).isTrue();
    }

    @Test @DisplayName("isValidImageType: gif 불허")
    void validImageType_gif() {
        assertThat(service.isValidImageType("image/gif")).isFalse();
    }

    @Test @DisplayName("isValidImageType: null 불허")
    void validImageType_null() {
        assertThat(service.isValidImageType(null)).isFalse();
    }

    @Test @DisplayName("isValidFileSize: 1MB 허용")
    void validSize_1mb() {
        assertThat(service.isValidFileSize(1024 * 1024)).isTrue();
    }

    @Test @DisplayName("isValidFileSize: 5MB 허용")
    void validSize_5mb() {
        assertThat(service.isValidFileSize(5 * 1024 * 1024)).isTrue();
    }

    @Test @DisplayName("isValidFileSize: 6MB 불허")
    void validSize_6mb() {
        assertThat(service.isValidFileSize(6 * 1024 * 1024)).isFalse();
    }

    @Test @DisplayName("isValidFileSize: 0 불허")
    void validSize_zero() {
        assertThat(service.isValidFileSize(0)).isFalse();
    }

    @Test @DisplayName("isValidFileSize: 음수 불허")
    void validSize_negative() {
        assertThat(service.isValidFileSize(-1)).isFalse();
    }

    @Test @DisplayName("StoredFileInfo: 모든 필드 접근")
    void storedFileInfo() {
        var info = new FileStorageService.StoredFileInfo("file.jpg", "/path", "/url", 800, 600, "abc123");
        assertThat(info.getStoredFileName()).isEqualTo("file.jpg");
        assertThat(info.getFilePath()).isEqualTo("/path");
        assertThat(info.getUrl()).isEqualTo("/url");
        assertThat(info.getWidth()).isEqualTo(800);
        assertThat(info.getHeight()).isEqualTo(600);
        assertThat(info.getChecksum()).isEqualTo("abc123");
    }
}
