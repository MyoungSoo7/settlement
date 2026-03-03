package github.lms.lemuel.settlement.adapter.in.web;

import github.lms.lemuel.settlement.adapter.in.web.response.SettlementPageResponse;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementSearchJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settlements")
@RequiredArgsConstructor
public class SettlementSearchController {

    private final SettlementSearchJdbcRepository searchRepository;

    @GetMapping("/search")
    public ResponseEntity<SettlementPageResponse> search(
            @RequestParam(required = false) String ordererName,
            @RequestParam(required = false) String productName,
            @RequestParam(required = false) Boolean isRefunded,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection
    ) {
        SettlementPageResponse response = searchRepository.search(
                ordererName, productName, isRefunded,
                status, startDate, endDate,
                page, size, sortBy, sortDirection);
        return ResponseEntity.ok(response);
    }
}