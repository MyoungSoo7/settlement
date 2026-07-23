package github.lms.lemuel.recovery.adapter.in.web;

import github.lms.lemuel.recovery.application.port.out.LoadSellerRecoveryPort;
import github.lms.lemuel.recovery.application.port.out.RecoveryAllocationPort;
import github.lms.lemuel.recovery.domain.RecoveryAllocation;
import github.lms.lemuel.recovery.domain.RecoveryStatus;
import github.lms.lemuel.recovery.domain.SellerRecovery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 지급후 회수 채권·상계 조회 콘솔 (seed-p0-6, 읽기 전용).
 *
 * <p>인가: {@code /admin/recoveries/**} 는 shared-common SecurityConfig 가 ADMIN/MANAGER 로 게이트.
 * 셀러 자기조회(JWT 주체 파생) API 는 셀러 포털 확장 시 별도 경로로 얹는다.
 */
@RestController
@RequestMapping("/admin/recoveries")
public class RecoveryAdminController {

    private final LoadSellerRecoveryPort loadSellerRecoveryPort;
    private final RecoveryAllocationPort recoveryAllocationPort;

    public RecoveryAdminController(LoadSellerRecoveryPort loadSellerRecoveryPort,
                                   RecoveryAllocationPort recoveryAllocationPort) {
        this.loadSellerRecoveryPort = loadSellerRecoveryPort;
        this.recoveryAllocationPort = recoveryAllocationPort;
    }

    /** 셀러의 채권 목록·미상계 잔액 합계·상계 이력을 한 화면 분량으로 반환한다. */
    @GetMapping
    public SellerRecoverySummaryResponse bySeller(@RequestParam Long sellerId) {
        List<SellerRecovery> recoveries = loadSellerRecoveryPort.findBySellerId(sellerId);
        List<RecoveryAllocation> allocations = recoveryAllocationPort.findAllocationsBySellerId(sellerId);
        BigDecimal outstandingTotal = recoveries.stream()
                .filter(recovery -> recovery.getStatus() == RecoveryStatus.OPEN)
                .map(SellerRecovery::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SellerRecoverySummaryResponse(
                sellerId,
                outstandingTotal,
                recoveries.stream().map(RecoveryView::from).toList(),
                allocations.stream().map(AllocationView::from).toList());
    }

    public record SellerRecoverySummaryResponse(Long sellerId, BigDecimal outstandingTotal,
                                                List<RecoveryView> recoveries,
                                                List<AllocationView> allocations) {
    }

    public record RecoveryView(Long id, Long sourceAdjustmentId, BigDecimal originalAmount,
                               BigDecimal allocatedAmount, BigDecimal outstanding, String status,
                               LocalDateTime createdAt, LocalDateTime closedAt) {

        static RecoveryView from(SellerRecovery recovery) {
            return new RecoveryView(recovery.getId(), recovery.getSourceAdjustmentId(),
                    recovery.getOriginalAmount(), recovery.getAllocatedAmount(), recovery.outstanding(),
                    recovery.getStatus().name(), recovery.getCreatedAt(), recovery.getClosedAt());
        }
    }

    public record AllocationView(Long id, Long recoveryId, Long settlementId,
                                 BigDecimal amount, LocalDateTime createdAt) {

        static AllocationView from(RecoveryAllocation allocation) {
            return new AllocationView(allocation.id(), allocation.recoveryId(),
                    allocation.settlementId(), allocation.amount(), allocation.createdAt());
        }
    }
}
