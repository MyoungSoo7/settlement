package github.lms.lemuel.point.adapter.in.web;

import github.lms.lemuel.point.adapter.in.web.dto.*;
import github.lms.lemuel.point.application.port.in.PointUseCase;
import github.lms.lemuel.point.domain.Point;
import github.lms.lemuel.point.domain.PointTransaction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Validated
@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PointController {

    private final PointUseCase pointUseCase;

    /**
     * 포인트 잔액 조회
     * GET /api/points/{userId}
     */
    @GetMapping("/{userId}")
    public ResponseEntity<PointResponse> getBalance(
            @PathVariable @Positive(message = "userId는 양수여야 합니다") Long userId
    ) {
        Point point = pointUseCase.getPointBalance(userId);
        return ResponseEntity.ok(PointResponse.from(point));
    }

    /**
     * 포인트 거래 내역 조회
     * GET /api/points/{userId}/transactions
     */
    @GetMapping("/{userId}/transactions")
    public ResponseEntity<List<PointTransactionResponse>> getTransactionHistory(
            @PathVariable @Positive(message = "userId는 양수여야 합니다") Long userId
    ) {
        List<PointTransactionResponse> history = pointUseCase.getTransactionHistory(userId).stream()
                .map(PointTransactionResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(history);
    }

    /**
     * 포인트 적립
     * POST /api/points/earn
     */
    @PostMapping("/earn")
    public ResponseEntity<PointTransactionResponse> earnPoints(@Valid @RequestBody EarnPointsRequest request) {
        PointTransaction tx = pointUseCase.earnPoints(new PointUseCase.EarnPointsCommand(
                request.getUserId(),
                request.getAmount(),
                request.getDescription(),
                request.getReferenceType(),
                request.getReferenceId()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(PointTransactionResponse.from(tx));
    }

    /**
     * 포인트 사용
     * POST /api/points/use
     */
    @PostMapping("/use")
    public ResponseEntity<PointTransactionResponse> usePoints(@Valid @RequestBody UsePointsRequest request) {
        PointTransaction tx = pointUseCase.usePoints(new PointUseCase.UsePointsCommand(
                request.getUserId(),
                request.getAmount(),
                request.getDescription(),
                request.getReferenceType(),
                request.getReferenceId()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(PointTransactionResponse.from(tx));
    }

    /**
     * 포인트 적립 취소
     * POST /api/points/cancel-earn
     */
    @PostMapping("/cancel-earn")
    public ResponseEntity<PointTransactionResponse> cancelEarnedPoints(@Valid @RequestBody CancelPointsRequest request) {
        PointTransaction tx = pointUseCase.cancelEarnedPoints(new PointUseCase.CancelPointsCommand(
                request.getUserId(),
                request.getAmount(),
                request.getDescription(),
                request.getReferenceType(),
                request.getReferenceId()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(PointTransactionResponse.from(tx));
    }

    /**
     * 포인트 사용 취소
     * POST /api/points/cancel-use
     */
    @PostMapping("/cancel-use")
    public ResponseEntity<PointTransactionResponse> cancelUsedPoints(@Valid @RequestBody CancelPointsRequest request) {
        PointTransaction tx = pointUseCase.cancelUsedPoints(new PointUseCase.CancelPointsCommand(
                request.getUserId(),
                request.getAmount(),
                request.getDescription(),
                request.getReferenceType(),
                request.getReferenceId()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(PointTransactionResponse.from(tx));
    }

    /**
     * 관리자 포인트 조정
     * POST /api/points/admin/adjust
     */
    @PostMapping("/admin/adjust")
    public ResponseEntity<PointTransactionResponse> adminAdjust(@Valid @RequestBody AdminAdjustRequest request) {
        PointTransaction tx = pointUseCase.adminAdjust(
                request.getUserId(),
                request.getAmount(),
                request.getDescription()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(PointTransactionResponse.from(tx));
    }
}
