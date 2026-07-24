package github.lms.lemuel.tax.application.service;

import github.lms.lemuel.tax.application.port.in.RegisterSellerTaxProfileUseCase;
import github.lms.lemuel.tax.application.port.out.LoadSellerTaxProfilePort;
import github.lms.lemuel.tax.application.port.out.SaveSellerTaxProfilePort;
import github.lms.lemuel.tax.domain.SellerTaxProfile;
import github.lms.lemuel.tax.domain.TaxType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 셀러 세무 프로필 레지스트리 서비스 — 등록·정정(upsert). payout 계좌 레지스트리와 동형.
 *
 * <p>이미 등록된 셀러면 세무유형·사업자등록번호를 정정하고, 없으면 신규 등록한다. 최초 등록 동시 경합
 * (PK seller_id UNIQUE 위반)은 낙관적으로 INSERT 후 {@link DataIntegrityViolationException} 을 잡아 재조회-정정으로
 * 수렴한다(SellerBankAccountRegistryService 와 동일한 근거).
 *
 * <p>로그에는 사업자등록번호 원문을 남기지 않는다(PII) — 세무유형만 기록.
 */
@Service
@Transactional
public class SellerTaxProfileRegistryService implements RegisterSellerTaxProfileUseCase {

    private static final Logger log = LoggerFactory.getLogger(SellerTaxProfileRegistryService.class);

    private final LoadSellerTaxProfilePort loadPort;
    private final SaveSellerTaxProfilePort savePort;

    public SellerTaxProfileRegistryService(LoadSellerTaxProfilePort loadPort,
                                           SaveSellerTaxProfilePort savePort) {
        this.loadPort = loadPort;
        this.savePort = savePort;
    }

    @Override
    public SellerTaxProfile register(Long sellerId, TaxType taxType, String businessRegNo) {
        Optional<SellerTaxProfile> existing = loadPort.findBySellerId(sellerId);
        if (existing.isPresent()) {
            return changeAndSave(existing.get(), taxType, businessRegNo);
        }

        log.info("[TaxProfileRegistry] 신규 등록: sellerId={}, taxType={}", sellerId, taxType);
        try {
            return savePort.save(SellerTaxProfile.register(sellerId, taxType, businessRegNo));
        } catch (DataIntegrityViolationException e) {
            log.warn("[TaxProfileRegistry] concurrent register race — retry as change: sellerId={}", sellerId);
            SellerTaxProfile winner = loadPort.findBySellerId(sellerId).orElseThrow(() -> e);
            return changeAndSave(winner, taxType, businessRegNo);
        }
    }

    private SellerTaxProfile changeAndSave(SellerTaxProfile profile, TaxType taxType, String businessRegNo) {
        profile.changeProfile(taxType, businessRegNo);
        log.info("[TaxProfileRegistry] 정정: sellerId={}, taxType={}", profile.getSellerId(), taxType);
        return savePort.save(profile);
    }
}
