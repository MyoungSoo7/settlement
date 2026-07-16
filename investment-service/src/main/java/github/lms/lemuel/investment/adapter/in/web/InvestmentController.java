package github.lms.lemuel.investment.adapter.in.web;

import github.lms.lemuel.investment.adapter.in.web.dto.BeginnerCheckResponse;
import github.lms.lemuel.investment.adapter.in.web.dto.FundingResponse;
import github.lms.lemuel.investment.adapter.in.web.dto.InvestmentOrderResponse;
import github.lms.lemuel.investment.adapter.in.web.dto.InvestmentScoreResponse;
import github.lms.lemuel.investment.adapter.in.web.dto.PlaceInvestmentOrderRequest;
import github.lms.lemuel.investment.adapter.in.web.dto.StockRecommendationsResponse;
import github.lms.lemuel.investment.adapter.out.persistence.InvestmentManualIdempotencyGuard;
import github.lms.lemuel.investment.application.port.in.CancelInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.in.ExecuteInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.in.GetBeginnerCheckUseCase;
import github.lms.lemuel.investment.application.port.in.GetFundingUseCase;
import github.lms.lemuel.investment.application.port.in.GetInvestmentScoreUseCase;
import github.lms.lemuel.investment.application.port.in.GetStockRecommendationsUseCase;
import github.lms.lemuel.investment.application.port.in.PlaceInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.in.PlaceInvestmentOrderUseCase.PlaceInvestmentOrderCommand;
import github.lms.lemuel.investment.application.port.out.LoadInvestmentOrderPort;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 투자점수 조회 + 초보 투자 체크 조회 + 투자 주문 신청/집행/취소/조회 + 재원 조회 API. JWT 인증 필수
 * (shared-common SecurityConfig 의 anyRequest authenticated 로 커버).
 *
 * <p>상태 변경 조작(신청 place·집행 execute·취소 cancel)은 옵셔널 {@code Idempotency-Key} 헤더를 받아
 * 더블클릭·재전송으로 인한 이중 집행(이중 이벤트)·중복 주문 생성을 앞단에서 차단한다. 동일 키 재요청은
 * 409(CONFLICT), 키 미제공 시 기존 동작(상태머신·@Version 낙관적 락에만 의존)을 유지한다.
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
    private final GetStockRecommendationsUseCase getStockRecommendationsUseCase;
    private final LoadInvestmentOrderPort loadInvestmentOrderPort;
    private final InvestmentManualIdempotencyGuard idempotency;

    public InvestmentController(GetInvestmentScoreUseCase getInvestmentScoreUseCase,
                                GetBeginnerCheckUseCase getBeginnerCheckUseCase,
                                PlaceInvestmentOrderUseCase placeInvestmentOrderUseCase,
                                ExecuteInvestmentOrderUseCase executeInvestmentOrderUseCase,
                                CancelInvestmentOrderUseCase cancelInvestmentOrderUseCase,
                                GetFundingUseCase getFundingUseCase,
                                GetStockRecommendationsUseCase getStockRecommendationsUseCase,
                                LoadInvestmentOrderPort loadInvestmentOrderPort,
                                InvestmentManualIdempotencyGuard idempotency) {
        this.getInvestmentScoreUseCase = getInvestmentScoreUseCase;
        this.getBeginnerCheckUseCase = getBeginnerCheckUseCase;
        this.placeInvestmentOrderUseCase = placeInvestmentOrderUseCase;
        this.executeInvestmentOrderUseCase = executeInvestmentOrderUseCase;
        this.cancelInvestmentOrderUseCase = cancelInvestmentOrderUseCase;
        this.getFundingUseCase = getFundingUseCase;
        this.getStockRecommendationsUseCase = getStockRecommendationsUseCase;
        this.loadInvestmentOrderPort = loadInvestmentOrderPort;
        this.idempotency = idempotency;
    }

    @GetMapping("/scores/{stockCode}")
    public ResponseEntity<InvestmentScoreResponse> score(@PathVariable String stockCode) {
        return ResponseEntity.ok(InvestmentScoreResponse.from(getInvestmentScoreUseCase.getScore(stockCode)));
    }

    /** 초보 투자 체크 — 점수 + 악재 뉴스(R3) + 시세 위치(R4·R5) + 거시 + 매매계획. budget 은 선택(원·양수). */
    @GetMapping("/checks/{stockCode}")
    public ResponseEntity<BeginnerCheckResponse> check(
            @PathVariable String stockCode,
            @RequestParam(required = false)
            @Positive(message = "budget 은 양수여야 합니다") BigDecimal budget) {
        return ResponseEntity.ok(BeginnerCheckResponse.from(getBeginnerCheckUseCase.getCheck(stockCode, budget)));
    }

    /** 최신 추천일 기준 종목 추천 세트 — 규칙 스크리닝 산출물(추천일·고지문 필수 포함). */
    @GetMapping("/recommendations")
    public ResponseEntity<StockRecommendationsResponse> recommendations() {
        return ResponseEntity.ok(StockRecommendationsResponse.from(
                getStockRecommendationsUseCase.getLatestRecommendations()));
    }

    @PostMapping("/orders")
    public ResponseEntity<InvestmentOrderResponse> place(
            @Valid @RequestBody PlaceInvestmentOrderRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        // 셀러 식별자는 요청 바디가 아니라 인증 주체에서 파생한다(IDOR 방지).
        long sellerId = callerSellerId(authentication);
        if (isDuplicate(idempotencyKey, "investment:place:" + sellerId, sellerId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        InvestmentOrderResponse body = InvestmentOrderResponse.from(placeInvestmentOrderUseCase.place(
                new PlaceInvestmentOrderCommand(sellerId, req.stockCode(), req.amount())));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/orders/{id}/execute")
    public ResponseEntity<InvestmentOrderResponse> execute(
            @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        long sellerId = callerSellerId(authentication);
        if (isDuplicate(idempotencyKey, "investment:execute:" + id, sellerId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.ok(InvestmentOrderResponse.from(
                executeInvestmentOrderUseCase.execute(id, sellerId)));
    }

    @PostMapping("/orders/{id}/cancel")
    public ResponseEntity<InvestmentOrderResponse> cancel(
            @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        long sellerId = callerSellerId(authentication);
        if (isDuplicate(idempotencyKey, "investment:cancel:" + id, sellerId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.ok(InvestmentOrderResponse.from(
                cancelInvestmentOrderUseCase.cancel(id, sellerId)));
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

    /**
     * Idempotency-Key 가 있고 이미 선점됐으면 true(중복 → 409). 키가 없으면 항상 false(멱등 미적용 —
     * 기존 동작 유지). operator 는 감사용으로 인증 주체의 셀러 식별자를 기록한다.
     */
    private boolean isDuplicate(String idempotencyKey, String endpoint, long sellerId) {
        return idempotencyKey != null && !idempotencyKey.isBlank()
                && !idempotency.claim(idempotencyKey, endpoint, String.valueOf(sellerId));
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
