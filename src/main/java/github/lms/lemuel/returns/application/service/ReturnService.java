package github.lms.lemuel.returns.application.service;

import github.lms.lemuel.returns.application.port.in.ReturnUseCase;
import github.lms.lemuel.returns.application.port.out.LoadReturnPort;
import github.lms.lemuel.returns.application.port.out.SaveReturnPort;
import github.lms.lemuel.returns.domain.ReturnOrder;
import github.lms.lemuel.returns.domain.ReturnStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ReturnService implements ReturnUseCase {

    private final LoadReturnPort loadReturnPort;
    private final SaveReturnPort saveReturnPort;

    @Override
    public ReturnOrder createReturn(CreateReturnCommand cmd) {
        log.info("반품/교환 생성 시작: orderId={}, userId={}, type={}", cmd.orderId(), cmd.userId(), cmd.type());

        ReturnOrder returnOrder = ReturnOrder.create(
                cmd.orderId(),
                cmd.userId(),
                cmd.type(),
                cmd.reason(),
                cmd.reasonDetail(),
                cmd.refundAmount()
        );

        ReturnOrder saved = saveReturnPort.save(returnOrder);

        log.info("반품/교환 생성 완료: returnId={}, orderId={}, type={}",
                saved.getId(), saved.getOrderId(), saved.getType());

        return saved;
    }

    @Override
    public ReturnOrder approveReturn(Long returnId) {
        log.info("반품/교환 승인: returnId={}", returnId);

        ReturnOrder returnOrder = findReturnOrThrow(returnId);
        returnOrder.approve();

        return saveReturnPort.save(returnOrder);
    }

    @Override
    public ReturnOrder rejectReturn(Long returnId, String reason) {
        log.info("반품/교환 거절: returnId={}, reason={}", returnId, reason);

        ReturnOrder returnOrder = findReturnOrThrow(returnId);
        returnOrder.reject(reason);

        return saveReturnPort.save(returnOrder);
    }

    @Override
    public ReturnOrder shipReturn(Long returnId, String trackingNumber, String carrier) {
        log.info("반품/교환 반송 발송: returnId={}, trackingNumber={}", returnId, trackingNumber);

        ReturnOrder returnOrder = findReturnOrThrow(returnId);
        returnOrder.ship(trackingNumber, carrier);

        return saveReturnPort.save(returnOrder);
    }

    @Override
    public ReturnOrder receiveReturn(Long returnId) {
        log.info("반품/교환 반송 수령: returnId={}", returnId);

        ReturnOrder returnOrder = findReturnOrThrow(returnId);
        returnOrder.receive();

        return saveReturnPort.save(returnOrder);
    }

    @Override
    public ReturnOrder completeReturn(Long returnId) {
        log.info("반품/교환 완료 처리: returnId={}", returnId);

        ReturnOrder returnOrder = findReturnOrThrow(returnId);

        if (returnOrder.isReturnType()) {
            returnOrder.complete();
            log.info("반품 완료 - 환불 처리 필요: returnId={}, refundAmount={}",
                    returnId, returnOrder.getRefundAmount());
        } else if (returnOrder.isExchangeType()) {
            returnOrder.complete();
            log.info("교환 완료: returnId={}", returnId);
        }

        return saveReturnPort.save(returnOrder);
    }

    @Override
    public ReturnOrder cancelReturn(Long returnId) {
        log.info("반품/교환 취소: returnId={}", returnId);

        ReturnOrder returnOrder = findReturnOrThrow(returnId);
        returnOrder.cancel();

        return saveReturnPort.save(returnOrder);
    }

    @Override
    @Transactional(readOnly = true)
    public ReturnOrder getReturn(Long returnId) {
        return findReturnOrThrow(returnId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReturnOrder> getReturnsByOrderId(Long orderId) {
        return loadReturnPort.findByOrderId(orderId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReturnOrder> getReturnsByUserId(Long userId) {
        return loadReturnPort.findByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReturnOrder> getReturnsByStatus(ReturnStatus status) {
        return loadReturnPort.findByStatus(status.name());
    }

    private ReturnOrder findReturnOrThrow(Long returnId) {
        return loadReturnPort.findById(returnId)
                .orElseThrow(() -> new IllegalArgumentException("Return not found: " + returnId));
    }
}
