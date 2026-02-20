package github.lms.lemuel.order.application.port.out;

public interface LoadUserForOrderPort {
    boolean existsById(Long userId);
}
