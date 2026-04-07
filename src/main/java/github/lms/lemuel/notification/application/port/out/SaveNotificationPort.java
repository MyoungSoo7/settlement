package github.lms.lemuel.notification.application.port.out;

import github.lms.lemuel.notification.domain.Notification;

import java.util.List;

public interface SaveNotificationPort {

    Notification save(Notification notification);

    List<Notification> saveAll(List<Notification> notifications);
}
