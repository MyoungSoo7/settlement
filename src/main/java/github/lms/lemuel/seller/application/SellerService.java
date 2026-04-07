package github.lms.lemuel.seller.application;

import github.lms.lemuel.seller.application.port.in.SellerUseCase;
import github.lms.lemuel.seller.application.port.out.LoadSellerPort;
import github.lms.lemuel.seller.application.port.out.SaveSellerPort;
import github.lms.lemuel.seller.domain.Seller;
import github.lms.lemuel.seller.domain.SellerStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SellerService implements SellerUseCase {

    private final LoadSellerPort loadSellerPort;
    private final SaveSellerPort saveSellerPort;

    @Override
    public Seller register(RegisterSellerCommand cmd) {
        log.info("판매자 등록 시작: userId={}, businessNumber={}", cmd.userId(), cmd.businessNumber());

        if (loadSellerPort.findByBusinessNumber(cmd.businessNumber()).isPresent()) {
            throw new IllegalStateException("이미 등록된 사업자번호입니다: " + cmd.businessNumber());
        }

        if (loadSellerPort.findByUserId(cmd.userId()).isPresent()) {
            throw new IllegalStateException("이미 판매자로 등록된 사용자입니다: userId=" + cmd.userId());
        }

        Seller seller = Seller.create(
                cmd.userId(),
                cmd.businessName(),
                cmd.businessNumber(),
                cmd.representativeName(),
                cmd.phone(),
                cmd.email()
        );

        Seller saved = saveSellerPort.save(seller);
        log.info("판매자 등록 완료: sellerId={}", saved.getId());
        return saved;
    }

    @Override
    public Seller approve(Long sellerId) {
        log.info("판매자 승인 시작: sellerId={}", sellerId);
        Seller seller = loadOrThrow(sellerId);
        seller.approve();
        Seller saved = saveSellerPort.save(seller);
        log.info("판매자 승인 완료: sellerId={}", sellerId);
        return saved;
    }

    @Override
    public Seller reject(Long sellerId) {
        log.info("판매자 거부 시작: sellerId={}", sellerId);
        Seller seller = loadOrThrow(sellerId);
        seller.reject();
        Seller saved = saveSellerPort.save(seller);
        log.info("판매자 거부 완료: sellerId={}", sellerId);
        return saved;
    }

    @Override
    public Seller suspend(Long sellerId) {
        log.info("판매자 정지 시작: sellerId={}", sellerId);
        Seller seller = loadOrThrow(sellerId);
        seller.suspend();
        Seller saved = saveSellerPort.save(seller);
        log.info("판매자 정지 완료: sellerId={}", sellerId);
        return saved;
    }

    @Override
    public Seller reactivate(Long sellerId) {
        log.info("판매자 재활성화 시작: sellerId={}", sellerId);
        Seller seller = loadOrThrow(sellerId);
        seller.reactivate();
        Seller saved = saveSellerPort.save(seller);
        log.info("판매자 재활성화 완료: sellerId={}", sellerId);
        return saved;
    }

    @Override
    public Seller updateBankInfo(Long sellerId, UpdateBankInfoCommand cmd) {
        log.info("판매자 계좌 정보 수정 시작: sellerId={}", sellerId);
        Seller seller = loadOrThrow(sellerId);
        seller.updateBankInfo(cmd.bankName(), cmd.accountNumber(), cmd.accountHolder());
        Seller saved = saveSellerPort.save(seller);
        log.info("판매자 계좌 정보 수정 완료: sellerId={}", sellerId);
        return saved;
    }

    @Override
    public Seller updateCommissionRate(Long sellerId, BigDecimal rate) {
        log.info("판매자 수수료율 변경 시작: sellerId={}, rate={}", sellerId, rate);
        Seller seller = loadOrThrow(sellerId);
        seller.updateCommissionRate(rate);
        Seller saved = saveSellerPort.save(seller);
        log.info("판매자 수수료율 변경 완료: sellerId={}", sellerId);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Seller getSeller(Long sellerId) {
        return loadOrThrow(sellerId);
    }

    @Override
    @Transactional(readOnly = true)
    public Seller getSellerByUserId(Long userId) {
        return loadSellerPort.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("판매자를 찾을 수 없습니다. userId=" + userId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Seller> getAllSellers() {
        return loadSellerPort.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Seller> getSellersByStatus(SellerStatus status) {
        return loadSellerPort.findByStatus(status);
    }

    private Seller loadOrThrow(Long sellerId) {
        return loadSellerPort.findById(sellerId)
                .orElseThrow(() -> new IllegalArgumentException("판매자를 찾을 수 없습니다. id=" + sellerId));
    }
}
