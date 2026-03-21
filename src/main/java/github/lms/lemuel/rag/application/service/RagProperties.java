package github.lms.lemuel.rag.application.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.rag")
@Getter
@Setter
public class RagProperties {
    private boolean enabled = true;
    private int maxResults = 5;
    private double similarityThreshold = 0.7;
    private String systemPrompt = "당신은 Lemuel 이커머스 시스템의 AI 어시스턴트입니다.";
}
