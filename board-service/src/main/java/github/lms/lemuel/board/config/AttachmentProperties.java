package github.lms.lemuel.board.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 첨부 저장 설정.
 *
 * @param baseDir 첨부 루트 디렉터리. 컨테이너에서는 볼륨을 여기에 마운트한다
 */
@ConfigurationProperties(prefix = "app.board.attachment")
public record AttachmentProperties(String baseDir) {

    public AttachmentProperties {
        if (baseDir == null || baseDir.isBlank()) {
            baseDir = "./data/board-attachments";
        }
    }
}
