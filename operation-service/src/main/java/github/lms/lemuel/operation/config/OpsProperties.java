package github.lms.lemuel.operation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * operation-service 운영 설정 (prefix=app.ops).
 *
 * <p>category-mapping 은 Alertmanager {@code labels.component} → SignalCategory 이름 매핑 —
 * 알람 룰이 늘어나면 yml 만 고치면 된다(코드 하드코딩 금지). 해석·폴백·경고는
 * IngestAlertService(AlertApplier) 책임.
 */
@Component
@ConfigurationProperties(prefix = "app.ops")
public class OpsProperties {

    /** labels.component → SignalCategory enum 이름 */
    private Map<String, String> categoryMapping = new HashMap<>();

    /** 매핑 실패 폴백 카테고리 (enum 이름) */
    private String defaultCategory = "UNKNOWN";

    /** REFIRED 타임라인 억제 간격 — repeat_interval 폭주 방지 */
    private Duration refireTimelineSuppression = Duration.ofMinutes(30);

    private final Webhook webhook = new Webhook();
    private final Signal signal = new Signal();
    private final Prometheus prometheus = new Prometheus();

    public Map<String, String> getCategoryMapping() {
        return categoryMapping;
    }

    public void setCategoryMapping(Map<String, String> categoryMapping) {
        this.categoryMapping = categoryMapping;
    }

    public String getDefaultCategory() {
        return defaultCategory;
    }

    public void setDefaultCategory(String defaultCategory) {
        this.defaultCategory = defaultCategory;
    }

    public Duration getRefireTimelineSuppression() {
        return refireTimelineSuppression;
    }

    public void setRefireTimelineSuppression(Duration refireTimelineSuppression) {
        this.refireTimelineSuppression = refireTimelineSuppression;
    }

    public Webhook getWebhook() {
        return webhook;
    }

    public Signal getSignal() {
        return signal;
    }

    public Prometheus getPrometheus() {
        return prometheus;
    }

    public static class Webhook {
        /**
         * Alertmanager webhook Bearer 토큰 — 값은 INTERNAL_API_KEY 재사용.
         * 미설정 시 검증 비활성(개발) + 경고 (shared-common InternalApiKeyFilter 와 동일 시맨틱).
         */
        private String token = "";

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }

    /** Phase 2 — Kafka 도메인 이벤트 → 신호 버킷 분모 집계 설정. */
    public static class Signal {
        /** metric_key(성공 이벤트) → 구독 토픽. 키가 곧 버킷의 metric_key 다. */
        private Map<String, String> topics = new HashMap<>();
        /** 버킷 폭(초). 5분=300. Phase 3 베이스라인 단위와 일치해야 한다. */
        private int bucketSeconds = 300;

        public Map<String, String> getTopics() {
            return topics;
        }

        public void setTopics(Map<String, String> topics) {
            this.topics = topics;
        }

        public int getBucketSeconds() {
            return bucketSeconds;
        }

        public void setBucketSeconds(int bucketSeconds) {
            this.bucketSeconds = bucketSeconds;
        }
    }

    /** Phase 2 — Prometheus 인스턴트 쿼리 폴링 → 게이지 버킷 설정. */
    public static class Prometheus {
        private boolean enabled = false;
        private String baseUrl = "http://localhost:9090";
        private long pollIntervalMs = 60_000;
        private int timeoutMs = 5_000;
        /** metric_key → PromQL 인스턴트 쿼리. */
        private Map<String, String> queries = new HashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public Map<String, String> getQueries() {
            return queries;
        }

        public void setQueries(Map<String, String> queries) {
            this.queries = queries;
        }
    }
}
