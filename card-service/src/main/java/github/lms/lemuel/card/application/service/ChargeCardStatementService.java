package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.ChargeCardStatementUseCase;
import github.lms.lemuel.card.application.port.in.OpenCardStatementUseCase;
import github.lms.lemuel.card.application.port.out.SaveCardStatementPort;
import github.lms.lemuel.card.domain.CardStatement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * 매입 확정액을 청구 명세서에 반영하는 서비스 — 청구 사이클의 입력.
 *
 * <p>청구주기는 매입 시각을 <b>KST</b> 로 환산한 연월이다. JVM 기본 타임존에 맡기면 UTC 로 도는 파드에서
 * 월말 자정 근처 매입이 전월로 잡혀, 마감된 명세서에 붙거나 다음 달로 밀린다(마감 배치도 KST 로 돈다).
 *
 * <p>납부 만기일은 청구월 <b>익월</b>의 지정일(기본 10일)이다 — 마감(익월 1일 01:00) 이후에 와야
 * 마감과 동시에 연체가 되는 일이 없다.
 */
@Service
public class ChargeCardStatementService implements ChargeCardStatementUseCase {

    private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Seoul");

    private final OpenCardStatementUseCase openCardStatementUseCase;
    private final SaveCardStatementPort saveCardStatementPort;
    private final int dueDayOfNextMonth;

    public ChargeCardStatementService(OpenCardStatementUseCase openCardStatementUseCase,
                                      SaveCardStatementPort saveCardStatementPort,
                                      @Value("${app.card.statement.due-day:10}") int dueDayOfNextMonth) {
        this.openCardStatementUseCase = openCardStatementUseCase;
        this.saveCardStatementPort = saveCardStatementPort;
        this.dueDayOfNextMonth = dueDayOfNextMonth;
    }

    @Override
    @Transactional
    public CardStatement charge(Long cardAccountId, Instant capturedAt, BigDecimal amount) {
        YearMonth period = YearMonth.from(capturedAt.atZone(BILLING_ZONE));
        CardStatement statement =
                openCardStatementUseCase.getOrOpenStatement(cardAccountId, period, dueDateFor(period));
        statement.addCharge(amount);
        return saveCardStatementPort.save(statement);
    }

    /** 익월 지정일. 지정일이 그 달에 없으면(31일 등) 말일로 맞춘다. */
    private LocalDate dueDateFor(YearMonth period) {
        YearMonth next = period.plusMonths(1);
        return next.atDay(Math.min(dueDayOfNextMonth, next.lengthOfMonth()));
    }
}
