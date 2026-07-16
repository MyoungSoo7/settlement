package github.lms.lemuel.notification.config

import github.lms.lemuel.notification.adapter.out.dedupe.InMemoryTtlDedupeStore
import github.lms.lemuel.notification.application.DedupeStore
import github.lms.lemuel.notification.application.NotificationChannel
import github.lms.lemuel.notification.application.NotificationDispatcher
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * Wires the application core. Channels are discovered by Spring (every
 * @Component implementing [NotificationChannel]) and injected as a list, so
 * adding a channel needs no change here.
 */
@Configuration
class NotificationConfig {

    @Bean
    fun dedupeStore(
        @Value("\${app.dedupe.ttl-minutes:30}") ttlMinutes: Long,
    ): DedupeStore = InMemoryTtlDedupeStore(ttl = Duration.ofMinutes(ttlMinutes))

    @Bean
    fun notificationDispatcher(
        channels: List<NotificationChannel>,
        dedupeStore: DedupeStore,
        @Value("\${app.dispatch.per-channel-timeout-ms:3000}") timeoutMs: Long,
        @Value("\${app.dispatch.max-attempts:3}") maxAttempts: Int,
        @Value("\${app.dispatch.base-backoff-ms:50}") backoffMs: Long,
    ): NotificationDispatcher = NotificationDispatcher(
        channels = channels,
        dedupe = dedupeStore,
        perChannelTimeoutMs = timeoutMs,
        maxAttempts = maxAttempts,
        baseBackoffMs = backoffMs,
    )
}
