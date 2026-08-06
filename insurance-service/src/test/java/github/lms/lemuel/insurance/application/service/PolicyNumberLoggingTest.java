package github.lms.lemuel.insurance.application.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyNumberLoggingTest {

    @Test
    void noOpCarrierStatusLog_doesNotContainPlaintextPolicyNumber() {
        Logger logger = (Logger) LoggerFactory.getLogger(NoOpCarrierPolicyStatusService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new NoOpCarrierPolicyStatusService()
                    .onCarrierPolicyStatusReceived("INS-SECRET-123456", "IN_FORCE");

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains("INS-SECRET-123456"));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
