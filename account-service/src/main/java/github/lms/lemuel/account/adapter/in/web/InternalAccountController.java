package github.lms.lemuel.account.adapter.in.web;

import github.lms.lemuel.account.application.port.in.AccountQueryUseCase;
import github.lms.lemuel.account.application.port.in.SellerFundingQuery.SellerFunding;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * account 가 자기 소유 GL 잔액을 내부 소비자에게 노출하는 API.
 *
 * <p>card-service 가 법인카드 한도를 산정할 때 셀러 재원을 여기서 조회한다. 같은 계산을
 * card 가 재구현하면 이벤트 유실 시 카드 한도와 회계 장부가 조용히 어긋나므로, 진실의 원천을
 * 회계 쪽 하나로 둔다 — 특정 셀러의 한도 근거를 시산표로 설명할 수 있게 된다.
 *
 * <p>인증은 shared-common {@code InternalApiKeyFilter}(X-Internal-Api-Key) 가 담당한다.
 * 운영에서는 {@code app.security.internal-key-required=true}(application-prod.yml)로 fail-closed.
 *
 * <p>★ account 는 소비 전용 서비스다 — 여기에 이벤트 발행 코드를 넣지 않는다(하드스톱).
 */
@Tag(name = "Internal - Account", description = "account 자기 GL 잔액 노출 (card-service 가 소비)")
@RestController
@RequestMapping("/internal/account")
public class InternalAccountController {

    private final AccountQueryUseCase accountQueryUseCase;

    public InternalAccountController(AccountQueryUseCase accountQueryUseCase) {
        this.accountQueryUseCase = accountQueryUseCase;
    }

    @Operation(summary = "셀러 재원 잔액 (account 원천)",
            description = "SELLER_PAYABLE(확정·미지급 정산금) + HOLDBACK_PAYABLE(유보분) 실체화 잔액. "
                    + "금액은 JSON 문자열(DATA-STANDARD N5). 잔액 행이 없으면 0.")
    @GetMapping("/sellers/{sellerId}/funding")
    public FundingResponse sellerFunding(@PathVariable String sellerId) {
        SellerFunding funding = accountQueryUseCase.sellerFunding(sellerId);
        return new FundingResponse(
                funding.sellerId(),
                funding.sellerPayable().toPlainString(),
                funding.holdbackPayable().toPlainString());
    }

    /** 금액은 문자열 — JS Number 변환으로 정밀도가 깎이면 한도가 틀어진다. */
    public record FundingResponse(String sellerId, String sellerPayable, String holdbackPayable) {
    }
}
