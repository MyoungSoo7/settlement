package github.lms.lemuel.seller.adapter.in.web;

import github.lms.lemuel.seller.adapter.in.web.dto.RegisterSellerRequest;
import github.lms.lemuel.seller.adapter.in.web.dto.SellerResponse;
import github.lms.lemuel.seller.adapter.in.web.dto.UpdateBankInfoRequest;
import github.lms.lemuel.seller.application.port.in.SellerUseCase;
import github.lms.lemuel.seller.domain.Seller;
import github.lms.lemuel.seller.domain.SellerStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 판매자(마켓플레이스) REST API
 * POST   /api/sellers                         - 판매자 등록
 * GET    /api/sellers/{id}                    - 판매자 조회
 * GET    /api/sellers/user/{userId}           - 사용자별 판매자 조회
 * GET    /api/sellers                         - 전체 판매자 목록 (관리자)
 * GET    /api/sellers/status/{status}         - 상태별 판매자 목록 (관리자)
 * PATCH  /api/sellers/{id}/approve            - 판매자 승인 (관리자)
 * PATCH  /api/sellers/{id}/reject             - 판매자 거부 (관리자)
 * PATCH  /api/sellers/{id}/suspend            - 판매자 정지 (관리자)
 * PATCH  /api/sellers/{id}/reactivate         - 판매자 재활성화 (관리자)
 * PUT    /api/sellers/{id}/bank-info          - 계좌 정보 수정
 * PATCH  /api/sellers/{id}/commission-rate    - 수수료율 변경 (관리자)
 */
@Validated
@RestController
@RequestMapping("/api/sellers")
@RequiredArgsConstructor
public class SellerController {

    private final SellerUseCase sellerUseCase;

    @PostMapping
    public ResponseEntity<SellerResponse> register(@Valid @RequestBody RegisterSellerRequest request) {
        Seller seller = sellerUseCase.register(new SellerUseCase.RegisterSellerCommand(
                request.userId(),
                request.businessName(),
                request.businessNumber(),
                request.representativeName(),
                request.phone(),
                request.email()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(SellerResponse.from(seller));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SellerResponse> getById(
            @PathVariable @Positive(message = "판매자 ID는 양수여야 합니다") Long id) {
        Seller seller = sellerUseCase.getSeller(id);
        return ResponseEntity.ok(SellerResponse.from(seller));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<SellerResponse> getByUserId(
            @PathVariable @Positive(message = "유저 ID는 양수여야 합니다") Long userId) {
        Seller seller = sellerUseCase.getSellerByUserId(userId);
        return ResponseEntity.ok(SellerResponse.from(seller));
    }

    @GetMapping
    public ResponseEntity<List<SellerResponse>> getAll() {
        List<SellerResponse> sellers = sellerUseCase.getAllSellers().stream()
                .map(SellerResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(sellers);
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<SellerResponse>> getByStatus(@PathVariable String status) {
        SellerStatus sellerStatus = SellerStatus.fromString(status);
        List<SellerResponse> sellers = sellerUseCase.getSellersByStatus(sellerStatus).stream()
                .map(SellerResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(sellers);
    }

    @PatchMapping("/{id}/approve")
    public ResponseEntity<SellerResponse> approve(
            @PathVariable @Positive(message = "판매자 ID는 양수여야 합니다") Long id) {
        Seller seller = sellerUseCase.approve(id);
        return ResponseEntity.ok(SellerResponse.from(seller));
    }

    @PatchMapping("/{id}/reject")
    public ResponseEntity<SellerResponse> reject(
            @PathVariable @Positive(message = "판매자 ID는 양수여야 합니다") Long id) {
        Seller seller = sellerUseCase.reject(id);
        return ResponseEntity.ok(SellerResponse.from(seller));
    }

    @PatchMapping("/{id}/suspend")
    public ResponseEntity<SellerResponse> suspend(
            @PathVariable @Positive(message = "판매자 ID는 양수여야 합니다") Long id) {
        Seller seller = sellerUseCase.suspend(id);
        return ResponseEntity.ok(SellerResponse.from(seller));
    }

    @PatchMapping("/{id}/reactivate")
    public ResponseEntity<SellerResponse> reactivate(
            @PathVariable @Positive(message = "판매자 ID는 양수여야 합니다") Long id) {
        Seller seller = sellerUseCase.reactivate(id);
        return ResponseEntity.ok(SellerResponse.from(seller));
    }

    @PutMapping("/{id}/bank-info")
    public ResponseEntity<SellerResponse> updateBankInfo(
            @PathVariable @Positive(message = "판매자 ID는 양수여야 합니다") Long id,
            @Valid @RequestBody UpdateBankInfoRequest request) {
        Seller seller = sellerUseCase.updateBankInfo(id, new SellerUseCase.UpdateBankInfoCommand(
                request.bankName(),
                request.accountNumber(),
                request.accountHolder()
        ));
        return ResponseEntity.ok(SellerResponse.from(seller));
    }

    @PatchMapping("/{id}/commission-rate")
    public ResponseEntity<SellerResponse> updateCommissionRate(
            @PathVariable @Positive(message = "판매자 ID는 양수여야 합니다") Long id,
            @RequestParam BigDecimal rate) {
        Seller seller = sellerUseCase.updateCommissionRate(id, rate);
        return ResponseEntity.ok(SellerResponse.from(seller));
    }
}
