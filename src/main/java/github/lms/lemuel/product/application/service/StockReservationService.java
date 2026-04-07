package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.StockReservationUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.LoadStockReservationPort;
import github.lms.lemuel.product.application.port.out.SaveProductPort;
import github.lms.lemuel.product.application.port.out.SaveStockReservationPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.StockReservation;
import github.lms.lemuel.product.domain.exception.InsufficientStockException;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class StockReservationService implements StockReservationUseCase {

    private static final int DEFAULT_TTL_MINUTES = 30;

    private final LoadStockReservationPort loadStockReservationPort;
    private final SaveStockReservationPort saveStockReservationPort;
    private final LoadProductPort loadProductPort;
    private final SaveProductPort saveProductPort;

    @Override
    public StockReservation reserve(Long productId, Long userId, int quantity) {
        log.info("재고 예약 시작: productId={}, userId={}, quantity={}", productId, userId, quantity);

        // 1. 상품 조회
        Product product = loadProductPort.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        // 2. 재고 확인 및 감소 (도메인 규칙에서 검증)
        try {
            product.decreaseStock(quantity);
        } catch (IllegalStateException e) {
            throw new InsufficientStockException(productId, quantity, product.getStockQuantity());
        }

        // 3. 상품 저장 (감소된 재고)
        saveProductPort.save(product);

        // 4. 예약 생성
        StockReservation reservation = StockReservation.create(productId, userId, quantity, DEFAULT_TTL_MINUTES);
        StockReservation savedReservation = saveStockReservationPort.save(reservation);

        log.info("재고 예약 완료: reservationId={}, productId={}, userId={}, quantity={}",
                savedReservation.getId(), productId, userId, quantity);

        return savedReservation;
    }

    @Override
    public StockReservation confirm(Long reservationId, Long orderId) {
        log.info("재고 예약 확정 시작: reservationId={}, orderId={}", reservationId, orderId);

        // 1. 예약 조회
        StockReservation reservation = loadStockReservationPort.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("Stock reservation not found: " + reservationId));

        // 2. 예약 확정 (도메인 규칙에서 검증)
        reservation.confirm(orderId);

        // 3. 저장
        StockReservation confirmed = saveStockReservationPort.save(reservation);

        log.info("재고 예약 확정 완료: reservationId={}, orderId={}", reservationId, orderId);

        return confirmed;
    }

    @Override
    public void release(Long reservationId) {
        log.info("재고 예약 해제 시작: reservationId={}", reservationId);

        // 1. 예약 조회
        StockReservation reservation = loadStockReservationPort.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("Stock reservation not found: " + reservationId));

        // 2. 예약 해제 (도메인 규칙에서 검증)
        reservation.release();

        // 3. 재고 복원
        Product product = loadProductPort.findById(reservation.getProductId())
                .orElseThrow(() -> new ProductNotFoundException(reservation.getProductId()));
        product.increaseStock(reservation.getQuantity());
        saveProductPort.save(product);

        // 4. 저장
        saveStockReservationPort.save(reservation);

        log.info("재고 예약 해제 완료: reservationId={}, productId={}, quantity={}",
                reservationId, reservation.getProductId(), reservation.getQuantity());
    }

    @Override
    public void releaseExpiredReservations() {
        log.info("만료된 재고 예약 정리 시작");

        List<StockReservation> expiredReservations = loadStockReservationPort.findExpiredReservations();

        int count = 0;
        for (StockReservation reservation : expiredReservations) {
            try {
                // 1. 만료 처리
                reservation.expire();

                // 2. 재고 복원
                Product product = loadProductPort.findById(reservation.getProductId())
                        .orElse(null);
                if (product != null) {
                    product.increaseStock(reservation.getQuantity());
                    saveProductPort.save(product);
                }

                // 3. 저장
                saveStockReservationPort.save(reservation);
                count++;
            } catch (Exception e) {
                log.error("만료된 예약 처리 실패: reservationId={}, error={}",
                        reservation.getId(), e.getMessage());
            }
        }

        log.info("만료된 재고 예약 정리 완료: {}건 처리", count);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockReservation> getActiveReservations(Long productId) {
        return loadStockReservationPort.findActiveByProductId(productId);
    }
}
