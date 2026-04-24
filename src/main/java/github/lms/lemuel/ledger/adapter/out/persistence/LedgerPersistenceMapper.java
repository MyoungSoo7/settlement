package github.lms.lemuel.ledger.adapter.out.persistence;

import github.lms.lemuel.ledger.domain.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class LedgerPersistenceMapper {

    public Account toDomain(AccountJpaEntity entity) {
        if (entity == null) return null;
        return new Account(
                entity.getId(),
                entity.getCode(),
                entity.getName(),
                AccountType.valueOf(entity.getType()),
                entity.getCreatedAt()
        );
    }

    public AccountJpaEntity toJpaEntity(Account domain) {
        if (domain == null) return null;
        return new AccountJpaEntity(
                domain.getId(),
                domain.getCode(),
                domain.getName(),
                domain.getType().name(),
                domain.getCreatedAt()
        );
    }

    public JournalEntryJpaEntity toJpaEntity(JournalEntry domain) {
        if (domain == null) return null;
        JournalEntryJpaEntity entity = new JournalEntryJpaEntity(
                domain.getEntryType(),
                domain.getReferenceType(),
                domain.getReferenceId(),
                domain.getDescription(),
                domain.getIdempotencyKey()
        );
        for (LedgerLine line : domain.getLines()) {
            LedgerLineJpaEntity lineEntity = new LedgerLineJpaEntity(
                    line.getAccount().getId(),
                    line.getSide().name(),
                    line.getAmount().amount()
            );
            entity.addLine(lineEntity);
        }
        return entity;
    }
}
