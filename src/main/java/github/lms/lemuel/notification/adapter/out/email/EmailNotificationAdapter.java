package github.lms.lemuel.notification.adapter.out.email;

import github.lms.lemuel.notification.application.port.out.SendNotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 이메일 알림 전송 어댑터
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailNotificationAdapter implements SendNotificationPort {

    private final JavaMailSender javaMailSender;

    @Override
    public boolean sendEmail(String to, String subject, String content) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(content);

            javaMailSender.send(message);
            log.info("이메일 전송 성공: to={}, subject={}", to, subject);
            return true;
        } catch (Exception e) {
            log.error("이메일 전송 실패: to={}, subject={}, error={}", to, subject, e.getMessage(), e);
            return false;
        }
    }
}
