package github.lms.lemuel.investment.adapter.in.web;

import github.lms.lemuel.investment.adapter.in.web.dto.BeginnerCheckResponse;
import github.lms.lemuel.investment.adapter.in.web.dto.FundingResponse;
import github.lms.lemuel.investment.adapter.in.web.dto.InvestmentOrderResponse;
import github.lms.lemuel.investment.adapter.in.web.dto.InvestmentScoreResponse;
import github.lms.lemuel.investment.adapter.in.web.dto.PlaceInvestmentOrderRequest;
import github.lms.lemuel.investment.application.port.in.CancelInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.in.ExecuteInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.in.GetBeginnerCheckUseCase;
import github.lms.lemuel.investment.application.port.in.GetFundingUseCase;
import github.lms.lemuel.investment.application.port.in.GetInvestmentScoreUseCase;
import github.lms.lemuel.investment.application.port.in.PlaceInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.in.PlaceInvestmentOrderUseCase.PlaceInvestmentOrderCommand;
import github.lms.lemuel.investment.application.port.out.LoadInvestmentOrderPort;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 투자점수 조회 + 초보 투자 체크 조회 + 투자 주문 신청/집행/취소/조회 + 재원 조회 API. JWT 인증 필수
 * (shared-common SecurityConfig 의 anyRequest authenticated 로 커버).
 */
@RestController
@RequestMapping("/api/investment")
public class InvestmentController {

    private final GetInvestmentScoreUseCase getInvestmentScoreUseCase;
    private final GetBeginnerCheckUseCase getBeginnerCheckUseCase;
    private final PlaceInvestmentOrderUseCase placeInvestmentOrderUseCase;
    private final ExecuteInvestmentOrderUseCase executeInvestmentOrderUseCase;
    private final CancelInvestmentOrderUseCase cancelInvestmentOrderUseCase;
    private final GetFundingUseCase getFundingUseCase;
    private final LoadInvestmentOrderPort loadInvestmentOrderPort;

    public InvestmentController(GetInvestmentScoreUseCase getInvestmentScoreUseCase,
                                GetBeginnerCheckUseCase getBeginnerCheckUseCase,
                                PlaceInvestmentOrderUseCase placeInvestmentOrderUseCase,
                                ExecuteInvestmentOrderUseCase executeInvestmentOrderUseCase,
                                CancelInvestmentOrderUseCase cancelInvestmentOrderUseCase,
                                GetFundingUseCase getFundingUseCase,
                                LoadInvestmentOrderPort loadInvestmentOrderPort) {
        this.getInvestmentScoreUseCase = getInvestmentScoreUseCase;
        this.getBeginnerCheckUseCase = getBeginnerCheckUseCase;
        this.placeInvestmentOrderUseCase = placeInvestmentOrderUseCase;
        this.executeInvestmentOrderUseCase = executeInvestmentOrderUseCase;
        this.cancelInvestmentOrderUseCase = cancelInvestmentOrderUseCase;
        this.getFundingUseCase = getFundingUseCase;
        this.loadInvestmentOrderPort = loadInvestmentOrderPort;
    }

    @GetMapping("/scores/{stockCode}")
    public ResponseEntity<InvestmentScoreResponse> score(@PathVariable String stockCode) {
        return ResponseEntity.ok(InvestmentScoreResponse.from(getInvestmentScoreUseCase.getScore(stockCode)));
    }

    /** 초보 투자 체크 — 점수 + 악재 뉴스(R3) + 시세 위치(R4·R5) + 거시 + 매매계획. budget 은 선택(원). */
    @GetMapping("/checks/{stockCode}")
    public ResponseEntity<BeginnerCheckResponse> check(@PathVariable String stockCode,
                                                       @RequestParam(required = false) BigDecimal budget) {
        return ResponseEntity.ok(BeginnerCheckResponse.from(getBeginnerCheckUseCase.getCheck(stockCode, budget)));
    }

    @PostMapping("/orders")
    public ResponseEntity<InvestmentOrderResponse> place(@Valid @RequestBody PlaceInvestmentOrderRequest req,
                                                         Authentication authentication) {
        // 셀러 식별자는 요청 바디가 아니라 인증 주체에서 파생한다(IDOR 방지).
        InvestmentOrderResponse body = InvestmentOrderResponse.from(placeInvestmentOrderUseCase.place(
                new PlaceInvestmentOrderCommand(callerSellerId(authentication), req.stockCode(), req.amount())));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/orders/{id}/execute")
    public ResponseEntity<InvestmentOrderResponse> execute(@PathVariable long id, Authentication authentication) {
        return ResponseEntity.ok(InvestmentOrderResponse.from(
                executeInvestmentOrderUseCase.execute(id, callerSellerId(authentication))));
    }

    @PostMapping("/orders/{id}/cancel")
    public ResponseEntity<InvestmentOrderResponse> cancel(@PathVariable long id, Authentication authentication) {
        return ResponseEntity.ok(InvestmentOrderResponse.from(
                cancelInvestmentOrderUseCase.cancel(id, callerSellerId(authentication))));
    }

    @GetMapping("/orders")
    public ResponseEntity<List<InvestmentOrderResponse>> bySeller(@RequestParam long sellerId,
                                                                  Authentication authentication) {
        requireSelf(sellerId, authentication);
        return ResponseEntity.ok(loadInvestmentOrderPort.findBySeller(sellerId).stream()
                .map(InvestmentOrderResponse::from)
                .toList());
    }

    @GetMapping("/funding/{sellerId}")
    public ResponseEntity<FundingResponse> funding(@PathVariable long sellerId, Authentication authentication) {
        requireSelf(sellerId, authentication);
        return ResponseEntity.ok(FundingResponse.from(getFundingUseCase.getFunding(sellerId)));
    }

    /** JWT 인증 주체에서 셀러 식별자(userId)를 추출한다. 미인증/식별불가면 403. */
    private static long callerSellerId(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthPrincipal principal
                && principal.userId() != null) {
            return principal.userId();
        }
        throw new AccessDeniedException("인증 주체에서 셀러 식별자를 확인할 수 없습니다.");
    }

    /** 요청한 셀러 리소스가 인증 주체 본인의 것인지 확인한다(타인 리소스 접근 → 403). */
    private static void requireSelf(long requestedSellerId, Authentication authentication) {
        if (requestedSellerId != callerSellerId(authentication)) {
            throw new AccessDeniedException("본인 소유가 아닌 셀러 리소스입니다. sellerId=" + requestedSellerId);
        }
    }
}
