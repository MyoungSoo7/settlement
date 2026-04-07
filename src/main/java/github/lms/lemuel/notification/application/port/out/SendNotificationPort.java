package github.lms.lemuel.notification.application.port.out;

public interface SendNotificationPort {

    boolean sendEmail(String to, String subject, String content);
}
