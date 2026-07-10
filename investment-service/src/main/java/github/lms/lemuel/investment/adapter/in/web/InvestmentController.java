package github.lms.lemuel.investment.adapter.in.web;

import github.lms.lemuel.investment.adapter.in.web.dto.FundingResponse;
import github.lms.lemuel.investment.adapter.in.web.dto.InvestmentOrderResponse;
import github.lms.lemuel.investment.adapter.in.web.dto.InvestmentScoreResponse;
import github.lms.lemuel.investment.adapter.in.web.dto.PlaceInvestmentOrderRequest;
import github.lms.lemuel.investment.application.port.in.CancelInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.in.ExecuteInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.in.GetFundingUseCase;
import github.lms.lemuel.investment.application.port.in.GetInvestmentScoreUseCase;
import github.lms.lemuel.investment.application.port.in.PlaceInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.in.PlaceInvestmentOrderUseCase.PlaceInvestmentOrderCommand;
import github.lms.lemuel.investment.application.port.out.LoadInvestmentOrderPort;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 투자점수 조회 + 투자 주문 신청/집행/취소/조회 + 재원 조회 API. JWT 인증 필수
 * (shared-common SecurityConfig 의 anyRequest authenticated 로 커버).
 */
@RestController
@RequestMapping("/api/investment")
public class InvestmentController {

    private final GetInvestmentScoreUseCase getInvestmentScoreUseCase;
    private final PlaceInvestmentOrderUseCase placeInvestmentOrderUseCase;
    private final ExecuteInvestmentOrderUseCase executeInvestmentOrderUseCase;
    private final CancelInvestmentOrderUseCase cancelInvestmentOrderUseCase;
    private final GetFundingUseCase getFundingUseCase;
    private final LoadInvestmentOrderPort loadInvestmentOrderPort;

    public InvestmentController(GetInvestmentScoreUseCase getInvestmentScoreUseCase,
                                PlaceInvestmentOrderUseCase placeInvestmentOrderUseCase,
                                ExecuteInvestmentOrderUseCase executeInvestmentOrderUseCase,
                                CancelInvestmentOrderUseCase cancelInvestmentOrderUseCase,
                                GetFundingUseCase getFundingUseCase,
                                LoadInvestmentOrderPort loadInvestmentOrderPort) {
        this.getInvestmentScoreUseCase = getInvestmentScoreUseCase;
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

    @PostMapping("/orders")
    public ResponseEntity<InvestmentOrderResponse> place(@Valid @RequestBody PlaceInvestmentOrderRequest req) {
        InvestmentOrderResponse body = InvestmentOrderResponse.from(placeInvestmentOrderUseCase.place(
                new PlaceInvestmentOrderCommand(req.sellerId(), req.stockCode(), req.amount())));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/orders/{id}/execute")
    public ResponseEntity<InvestmentOrderResponse> execute(@PathVariable long id) {
        return ResponseEntity.ok(InvestmentOrderResponse.from(executeInvestmentOrderUseCase.execute(id)));
    }

    @PostMapping("/orders/{id}/cancel")
    public ResponseEntity<InvestmentOrderResponse> cancel(@PathVariable long id) {
        return ResponseEntity.ok(InvestmentOrderResponse.from(cancelInvestmentOrderUseCase.cancel(id)));
    }

    @GetMapping("/orders")
    public ResponseEntity<List<InvestmentOrderResponse>> bySeller(@RequestParam long sellerId) {
        return ResponseEntity.ok(loadInvestmentOrderPort.findBySeller(sellerId).stream()
                .map(InvestmentOrderResponse::from)
                .toList());
    }

    @GetMapping("/funding/{sellerId}")
    public ResponseEntity<FundingResponse> funding(@PathVariable long sellerId) {
        return ResponseEntity.ok(FundingResponse.from(getFundingUseCase.getFunding(sellerId)));
    }
}
