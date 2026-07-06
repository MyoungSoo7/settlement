package github.lms.lemuel.economics.application.port.in;

import java.time.LocalDate;

public interface SyncIndicatorsUseCase {

    /** indicatorCode=null 이면 카탈로그 전체. [from, to] 관측치를 ECOS 에서 받아 upsert. */
    SyncResult syncIndicators(String indicatorCode, LocalDate from, LocalDate to);
}
