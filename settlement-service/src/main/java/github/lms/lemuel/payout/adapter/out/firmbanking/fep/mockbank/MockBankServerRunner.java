package github.lms.lemuel.payout.adapter.out.firmbanking.fep.mockbank;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 시연·개발용 모의 은행 서버 러너 — {@code app.fep.mock-bank.enabled=true} 일 때만 기동.
 *
 * <p>운영에서는 실제 은행 FEP 에 접속하므로 이 러너는 비활성이 기본이다.
 */
@Component
@ConditionalOnProperty(name = "app.fep.mock-bank.enabled", havingValue = "true")
public class MockBankServerRunner {

    private final MockBankServer server;

    public MockBankServerRunner(
            @Value("${app.fep.mock-bank.port:9410}") int port,
            @Value("${app.fep.mock-bank.slow-delay-ms:5000}") long slowDelayMillis) {
        this.server = new MockBankServer(port, slowDelayMillis);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() throws IOException {
        server.start();
    }

    @PreDestroy
    public void stop() {
        server.stop();
    }
}
