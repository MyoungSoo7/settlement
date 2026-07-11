package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.loan.adapter.in.web.dto.CorporateCreditResponse;
import github.lms.lemuel.loan.adapter.in.web.dto.CorporateLoanRequestBody;
import github.lms.lemuel.loan.adapter.in.web.dto.CorporateLoanResponse;
import github.lms.lemuel.loan.application.port.in.DisburseCorporateLoanUseCase;
import github.lms.lemuel.loan.application.port.in.EvaluateCorporateCreditUseCase;
import github.lms.lemuel.loan.application.port.in.RequestCorporateLoanUseCase;
import github.lms.lemuel.loan.application.port.in.RequestCorporateLoanUseCase.RequestCorporateLoanCommand;
import github.lms.lemuel.loan.application.port.out.LoadCorporateLoanPort;
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
 * 기업(코스피/코스닥 상장사) 신용대출 — CEO 메뉴용 신용평가/신청/실행/조회 API.
 */
@RestController
@RequestMapping("/loans/corporate")
public class CorporateLoanController {

    private static final int RECENT_LIMIT = 50;

    private final EvaluateCorporateCreditUseCase evaluateCorporateCreditUseCase;
    private final RequestCorporateLoanUseCase requestCorporateLoanUseCase;
    private final DisburseCorporateLoanUseCase disburseCorporateLoanUseCase;
    private final LoadCorporateLoanPort loadCorporateLoanPort;

    public CorporateLoanController(EvaluateCorporateCreditUseCase evaluateCorporateCreditUseCase,
                                   RequestCorporateLoanUseCase requestCorporateLoanUseCase,
                                   DisburseCorporateLoanUseCase disburseCorporateLoanUseCase,
                                   LoadCorporateLoanPort loadCorporateLoanPort) {
        this.evaluateCorporateCreditUseCase = evaluateCorporateCreditUseCase;
        this.requestCorporateLoanUseCase = requestCorporateLoanUseCase;
        this.disburseCorporateLoanUseCase = disburseCorporateLoanUseCase;
        this.loadCorporateLoanPort = loadCorporateLoanPort;
    }

    @GetMapping("/credit/{stockCode}")
    public ResponseEntity<CorporateCreditResponse> credit(@PathVariable String stockCode) {
        return ResponseEntity.ok(CorporateCreditResponse.from(
                evaluateCorporateCreditUseCase.evaluate(stockCode)));
    }

    @PostMapping
    public ResponseEntity<CorporateLoanResponse> request(@Valid @RequestBody CorporateLoanRequestBody req) {
        CorporateLoanResponse body = CorporateLoanResponse.from(requestCorporateLoanUseCase.request(
                new RequestCorporateLoanCommand(req.stockCode(), req.principal(), req.termDays())));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/{id}/disburse")
    public ResponseEntity<CorporateLoanResponse> disburse(@PathVariable Long id) {
        return ResponseEntity.ok(CorporateLoanResponse.from(disburseCorporateLoanUseCase.disburse(id)));
    }

    @GetMapping
    public ResponseEntity<List<CorporateLoanResponse>> list(
            @RequestParam(required = false) String stockCode) {
        List<CorporateLoanResponse> body = (stockCode == null || stockCode.isBlank()
                ? loadCorporateLoanPort.findRecent(RECENT_LIMIT)
                : loadCorporateLoanPort.findByStockCode(stockCode))
                .stream()
                .map(CorporateLoanResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }
}
