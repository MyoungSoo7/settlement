package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentTender;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Payment Persistence Adapter — 분할결제 (tenders) 저장/로드 포함.
 *
 * <p>분할결제(SplitPayment) 인 경우 부모 Payment 저장 후 자식 PaymentTender 들을 일괄 저장.
 * 로드 시에도 tenders 를 함께 hydrate 하여 도메인 불변식 (tender 합계 == amount) 검증 가능.
 */
@Component
public class PaymentPersistenceAdapter implements LoadPaymentPort, SavePaymentPort {

    private final PaymentJpaRepository paymentJpaRepository;
    private final PaymentMapper paymentMapper;
    private final SpringDataPaymentTenderRepository tenderRepository;

    public PaymentPersistenceAdapter(PaymentJpaRepository paymentJpaRepository,
                                      PaymentMapper paymentMapper,
                                      SpringDataPaymentTenderRepository tenderRepository) {
        this.paymentJpaRepository = paymentJpaRepository;
        this.paymentMapper = paymentMapper;
        this.tenderRepository = tenderRepository;
    }

    @Override
    public Optional<PaymentDomain> loadById(Long id) {
        return paymentJpaRepository.findById(id).map(this::toDomainWithTenders);
    }

    @Override
    public Optional<PaymentDomain> loadByIdForUpdate(Long id) {
        return paymentJpaRepository.findByIdForUpdate(id).map(this::toDomainWithTenders);
    }

    @Override
    public Optional<PaymentDomain> loadByOrderId(Long orderId) {
        return paymentJpaRepository.findByOrderId(orderId).map(this::toDomainWithTenders);
    }

    @Override
    public PaymentDomain save(PaymentDomain paymentDomain) {
        PaymentJpaEntity entity = paymentMapper.toJpaEntity(paymentDomain);
        PaymentJpaEntity savedEntity = paymentJpaRepository.save(entity);

        if (paymentDomain.isSplit()) {
            // 도메인 불변식 검증
            paymentDomain.validateTenderSum();
            for (PaymentTender t : paymentDomain.getTenders()) {
                if (t.getId() == null) {
                    // 신규 tender — INSERT
                    PaymentTenderJpaEntity tenderEntity = new PaymentTenderJpaEntity(
                            null, savedEntity.getId(), t.getType(), t.getAmount(),
                            t.getRefundedAmount(), t.getPgTransactionId(), t.getStatus(),
                            t.getSequence(), t.getCreatedAt(), t.getUpdatedAt()
                    );
                    tenderRepository.save(tenderEntity);
                } else {
                    // 기존 tender — UPDATE (환불 누적, 상태 변경)
                    PaymentTenderJpaEntity existing = tenderRepository.findById(t.getId())
                            .orElseThrow(() -> new IllegalStateException("Tender 사라짐: " + t.getId()));
                    existing.applyState(t.getRefundedAmount(), t.getPgTransactionId(),
                            t.getStatus(), t.getUpdatedAt());
                    tenderRepository.save(existing);
                }
            }
        }

        return toDomainWithTenders(savedEntity);
    }

    private PaymentDomain toDomainWithTenders(PaymentJpaEntity entity) {
        PaymentDomain domain = paymentMapper.toDomain(entity);
        List<PaymentTender> tenders = tenderRepository
                .findByPaymentIdOrderBySequenceAsc(entity.getId()).stream()
                .map(PaymentPersistenceAdapter::toTenderDomain)
                .toList();
        domain.replaceTenders(tenders);
        return domain;
    }

    private static PaymentTender toTenderDomain(PaymentTenderJpaEntity e) {
        return PaymentTender.rehydrate(
                e.getId(), e.getPaymentId(), e.getTenderType(), e.getAmount(),
                e.getRefundedAmount(), e.getPgTransactionId(), e.getStatus(),
                e.getSequence(), e.getCreatedAt(), e.getUpdatedAt()
        );
    }
}
