package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.payout.application.port.in.RegisterSellerBankAccountUseCase;
import github.lms.lemuel.payout.application.port.out.LoadSellerBankAccountRegistrationPort;
import github.lms.lemuel.payout.application.port.out.SaveSellerBankAccountRegistrationPort;
import github.lms.lemuel.payout.domain.SellerBankAccountRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 셀러 지급 계좌 레지스트리 서비스 — 등록·정정(upsert).
 *
 * <p>이미 등록된 셀러면 계좌를 정정(도메인 {@code changeAccount})하고, 없으면 신규 등록한다. 정정은
 * 반송 후 재지급이 <b>정정된 계좌</b>를 신선 로드하도록 하는 유일한 경로다(반송 재지급의 선행 단계).
 *
 * <p><b>최초 등록 동시성(독립 코드리뷰 LOW 시정)</b>: 두 요청이 동시에 같은 셀러를 "미등록"으로 관찰하면
 * 둘 다 신규 INSERT 를 시도해 PK({@code seller_id}) UNIQUE 위반이 난다. 관리자 저빈도 조작이라 비관적
 * 잠금 대신 낙관적으로 INSERT 를 먼저 시도하고, 경합 시({@link DataIntegrityViolationException}) 재조회 후
 * 정정(변경)으로 수렴한다 — 승자의 행이 이미 있으므로 이 재시도는 반드시 업데이트로 성공한다. 영속
 * 어댑터의 {@code saveAndFlush} 가 이 예외를 트랜잭션 커밋 시점이 아니라 저장 호출 시점에 동기적으로
 * 드러내므로 여기서 안전하게 잡을 수 있다.
 */
@Service
@Transactional
public class SellerBankAccountRegistryService implements RegisterSellerBankAccountUseCase {

    private static final Logger log = LoggerFactory.getLogger(SellerBankAccountRegistryService.class);

    private final LoadSellerBankAccountRegistrationPort loadPort;
    private final SaveSellerBankAccountRegistrationPort savePort;

    public SellerBankAccountRegistryService(LoadSellerBankAccountRegistrationPort loadPort,
                                            SaveSellerBankAccountRegistrationPort savePort) {
        this.loadPort = loadPort;
        this.savePort = savePort;
    }

    @Override
    public SellerBankAccountRegistration register(Long sellerId, String bankCode,
                                                  String accountNumber, String accountHolder) {
        Optional<SellerBankAccountRegistration> existing = loadPort.findBySellerId(sellerId);
        if (existing.isPresent()) {
            return changeAndSave(existing.get(), bankCode, accountNumber, accountHolder);
        }

        log.info("[SellerAcctRegistry] 신규 등록: sellerId={}, bank={}", sellerId, bankCode);
        try {
            return savePort.save(SellerBankAccountRegistration.register(
                    sellerId, bankCode, accountNumber, accountHolder));
        } catch (DataIntegrityViolationException e) {
            // 동시 최초 등록 경합 — 다른 요청이 먼저 INSERT 를 커밋(승자의 행이 이제 존재). 재조회 후
            // 정정으로 수렴한다(패자도 결국 정정 의미로 반영되므로 데이터 유실 없음).
            log.warn("[SellerAcctRegistry] concurrent register race — retry as change: sellerId={}, reason={}",
                    sellerId, e.toString());
            SellerBankAccountRegistration winner = loadPort.findBySellerId(sellerId).orElseThrow(() -> e);
            return changeAndSave(winner, bankCode, accountNumber, accountHolder);
        }
    }

    private SellerBankAccountRegistration changeAndSave(SellerBankAccountRegistration registration,
                                                        String bankCode, String accountNumber,
                                                        String accountHolder) {
        registration.changeAccount(bankCode, accountNumber, accountHolder);
        log.info("[SellerAcctRegistry] 등록정보 정정: sellerId={}, bank={}", registration.getSellerId(), bankCode);
        return savePort.save(registration);
    }
}
