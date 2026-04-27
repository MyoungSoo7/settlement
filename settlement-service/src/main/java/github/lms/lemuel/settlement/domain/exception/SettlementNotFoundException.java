package github.lms.lemuel.settlement.domain.exception;

public class SettlementNotFoundException extends RuntimeException {
    public SettlementNotFoundException(String message) {
        super(message);
    }

    public SettlementNotFoundException(Long settlementId) {
        super("Settlement not found with id: " + settlementId);
    }
}
