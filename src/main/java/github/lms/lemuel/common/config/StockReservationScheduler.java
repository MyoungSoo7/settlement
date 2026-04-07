package github.lms.lemuel.common.config;

import github.lms.lemuel.product.application.port.in.StockReservationUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StockReservationScheduler {

    private final StockReservationUseCase stockReservationUseCase;

    @Scheduled(fixedRate = 60000) // every 1 minute
    public void cleanupExpiredReservations() {
        log.info("Running expired stock reservation cleanup...");
        stockReservationUseCase.releaseExpiredReservations();
    }
}
