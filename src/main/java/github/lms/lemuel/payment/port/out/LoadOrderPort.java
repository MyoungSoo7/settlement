package github.lms.lemuel.payment.port.out;

import java.math.BigDecimal;

/**
 * Port for loading order information from Order bounded context
 */
public interface LoadOrderPort {
    OrderInfo loadOrder(Long orderId);
    
    class OrderInfo {
        private final Long id;
        private final BigDecimal amount;
        private final String status;
        
        public OrderInfo(Long id, BigDecimal amount, String status) {
            this.id = id;
            this.amount = amount;
            this.status = status;
        }
        
        public Long getId() {
            return id;
        }
        
        public BigDecimal getAmount() {
            return amount;
        }
        
        public String getStatus() {
            return status;
        }
        
        public boolean isCreated() {
            return "CREATED".equals(status);
        }
    }
}
