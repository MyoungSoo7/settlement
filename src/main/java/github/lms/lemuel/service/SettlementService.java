package github.lms.lemuel.service;

import github.lms.lemuel.domain.Settlement;
import github.lms.lemuel.domain.User;
import github.lms.lemuel.event.SettlementIndexEvent;
import github.lms.lemuel.repository.SettlementRepository;
import github.lms.lemuel.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 정산 관리 서비스
 * 정산 승인/반려 처리를 담당
 */
@Service
public class SettlementService {

    private static final Logger logger = LoggerFactory.getLogger(SettlementService.class);

    private final SettlementRepository settlementRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public SettlementService(SettlementRepository settlementRepository,
                           UserRepository userRepository,
                           ApplicationEventPublisher eventPublisher) {
        this.settlementRepository = settlementRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 정산 승인 처리
     * @param settlementId 정산 ID
     * @param adminUserId 승인자 ID
     * @throws IllegalStateException 정산 상태가 WAITING_APPROVAL이 아닌 경우
     * @throws IllegalArgumentException 정산이 존재하지 않거나 관리자가 아닌 경우
     */
    @Transactional
    public Settlement approveSettlement(Long settlementId, Long adminUserId) {
        // 관리자 권한 체크
        User admin = userRepository.findById(adminUserId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + adminUserId));

        if (!"ADMIN".equals(admin.getRole())) {
            throw new IllegalArgumentException("관리자 권한이 필요합니다.");
        }

        // 정산 조회
        Settlement settlement = settlementRepository.findById(settlementId)
            .orElseThrow(() -> new IllegalArgumentException("정산을 찾을 수 없습니다: " + settlementId));

        // 상태 검증
        if (settlement.getStatus() != Settlement.SettlementStatus.WAITING_APPROVAL) {
            throw new IllegalStateException("승인 대기 상태가 아닙니다. 현재 상태: " + settlement.getStatus());
        }

        // 승인 처리
        settlement.setStatus(Settlement.SettlementStatus.APPROVED);
        settlement.setApprovedBy(adminUserId);
        settlement.setApprovedAt(LocalDateTime.now());

        Settlement savedSettlement = settlementRepository.save(settlement);

        logger.info("정산 승인 완료: settlementId={}, approvedBy={}", settlementId, adminUserId);

        // Elasticsearch 인덱싱 이벤트 발행
        eventPublisher.publishEvent(
            new SettlementIndexEvent(List.of(settlementId), SettlementIndexEvent.IndexEventType.APPROVED)
        );

        return savedSettlement;
    }

    /**
     * 정산 반려 처리
     * @param settlementId 정산 ID
     * @param adminUserId 반려자 ID
     * @param reason 반려 사유
     * @throws IllegalStateException 정산 상태가 WAITING_APPROVAL이 아닌 경우
     * @throws IllegalArgumentException 정산이 존재하지 않거나 관리자가 아닌 경우
     */
    @Transactional
    public Settlement rejectSettlement(Long settlementId, Long adminUserId, String reason) {
        // 관리자 권한 체크
        User admin = userRepository.findById(adminUserId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + adminUserId));

        if (!"ADMIN".equals(admin.getRole())) {
            throw new IllegalArgumentException("관리자 권한이 필요합니다.");
        }

        // 정산 조회
        Settlement settlement = settlementRepository.findById(settlementId)
            .orElseThrow(() -> new IllegalArgumentException("정산을 찾을 수 없습니다: " + settlementId));

        // 상태 검증
        if (settlement.getStatus() != Settlement.SettlementStatus.WAITING_APPROVAL) {
            throw new IllegalStateException("승인 대기 상태가 아닙니다. 현재 상태: " + settlement.getStatus());
        }

        // 반려 처리
        settlement.setStatus(Settlement.SettlementStatus.REJECTED);
        settlement.setRejectedBy(adminUserId);
        settlement.setRejectedAt(LocalDateTime.now());
        settlement.setRejectionReason(reason);

        Settlement savedSettlement = settlementRepository.save(settlement);

        logger.info("정산 반려 완료: settlementId={}, rejectedBy={}, reason={}", settlementId, adminUserId, reason);

        // Elasticsearch 인덱싱 이벤트 발행
        eventPublisher.publishEvent(
            new SettlementIndexEvent(List.of(settlementId), SettlementIndexEvent.IndexEventType.REJECTED)
        );

        return savedSettlement;
    }

    /**
     * 승인 대기 중인 정산 목록 조회
     */
    @Transactional(readOnly = true)
    public List<Settlement> getWaitingApprovalSettlements() {
        return settlementRepository.findByStatus(Settlement.SettlementStatus.WAITING_APPROVAL);
    }
}
