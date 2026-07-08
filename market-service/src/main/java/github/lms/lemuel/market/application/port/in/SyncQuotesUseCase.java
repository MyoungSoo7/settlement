package github.lms.lemuel.market.application.port.in;

import java.time.LocalDate;

public interface SyncQuotesUseCase {

    /** 특정 거래일(baseDate)의 전 종목 시세를 금융위 API 에서 받아 종목 마스터+시세를 upsert. */
    SyncResult syncQuotes(LocalDate baseDate);
}
