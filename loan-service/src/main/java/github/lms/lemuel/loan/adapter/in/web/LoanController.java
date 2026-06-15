package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.loan.adapter.in.web.dto.LoanRequest;
import github.lms.lemuel.loan.adapter.in.web.dto.LoanResponse;
import github.lms.lemuel.loan.application.port.in.DisburseLoanUseCase;
import github.lms.lemuel.loan.application.port.in.RequestLoanUseCase;
import github.lms.lemuel.loan.application.port.in.RequestLoanUseCase.RequestLoanCommand;
import github.lms.lemuel.loan.application.port.out.LoadLoanPort;
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
 * 선정산 대출 신청/실행/조회 API.
 */
@RestController
@RequestMapping("/loans")
public class LoanController {

    private final RequestLoanUseCase requestLoanUseCase;
    private final DisburseLoanUseCase disburseLoanUseCase;
    private final LoadLoanPort loadLoanPort;

    public LoanController(RequestLoanUseCase requestLoanUseCase,
                          DisburseLoanUseCase disburseLoanUseCase,
                          LoadLoanPort loadLoanPort) {
        this.requestLoanUseCase = requestLoanUseCase;
        this.disburseLoanUseCase = disburseLoanUseCase;
        this.loadLoanPort = loadLoanPort;
    }

    @PostMapping
    public ResponseEntity<LoanResponse> request(@Valid @RequestBody LoanRequest req) {
        LoanResponse body = LoanResponse.from(requestLoanUseCase.request(
                new RequestLoanCommand(req.sellerId(), req.principal(), req.financingDays())));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/{id}/disburse")
    public ResponseEntity<LoanResponse> disburse(@PathVariable Long id) {
        return ResponseEntity.ok(LoanResponse.from(disburseLoanUseCase.disburse(id)));
    }

    @GetMapping
    public ResponseEntity<List<LoanResponse>> bySeller(@RequestParam Long sellerId) {
        return ResponseEntity.ok(loadLoanPort.findBySeller(sellerId).stream()
                .map(LoanResponse::from)
                .toList());
    }
}
