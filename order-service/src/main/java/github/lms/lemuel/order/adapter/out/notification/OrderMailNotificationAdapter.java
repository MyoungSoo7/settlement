package github.lms.lemuel.order.adapter.out.notification;

import github.lms.lemuel.order.application.port.out.SendOrderNotificationPort;
import github.lms.lemuel.order.domain.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 이메일을 통한 주문 알림 전송 어댑터
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderMailNotificationAdapter implements SendOrderNotificationPort {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from:noreply@lemuel.com}")
    private String fromEmail;

    @Override
    public void sendOrderConfirmation(String email, Order order) {
        log.info("주문 확인 메일 발송 시도: to={}, orderId={}", email, order.getId());

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(email);
            message.setSubject("[Lemuel] 주문이 완료되었습니다.");
            message.setText(String.format(
                    "안녕하세요.\n\nLemuel에서 주문하신 내역을 안내드립니다.\n\n" +
                    "주문 번호: %d\n" +
                    "상품 ID: %d\n" +
                    "결제 금액: %s\n" +
                    "주문 일시: %s\n\n" +
                    "이용해 주셔서 감사합니다.",
                    order.getId(),
                    order.getProductId(),
                    order.getAmount().toString(),
                    order.getCreatedAt().toString()
            ));

            mailSender.send(message);
            log.info("주문 확인 메일 발송 완료: to={}, orderId={}", email, order.getId());
        } catch (Exception e) {
            log.error("메일 발송 중 오류 발생: to={}, orderId={}, error={}", email, order.getId(), e.getMessage());
            // 알림 실패가 비즈니스 로직(주문 생성) 전체를 롤백시키지 않도록 예외를 잡음
            // 또는 비동기로 처리하는 것이 좋으나, 여기서는 간단히 로그만 남김
        }
    }
}
