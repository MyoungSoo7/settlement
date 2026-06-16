package github.lms.lemuel.projectionbackfill;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * settlement 프로젝션 백필 운영 엔드포인트 (ADR 0020 Phase 4 Chunk 3, ADMIN 전용 — SecurityConfig 강제).
 */
@Tag(name = "Admin - Settlement Projection", description = "settlement 프로젝션 백필(기존 order 데이터 시드)")
@RestController
@RequestMapping("/admin/settlement-projection")
@RequiredArgsConstructor
public class SettlementProjectionBackfillController {

    private final BackfillSettlementProjectionsUseCase backfillUseCase;

    @Operation(summary = "settlement 프로젝션 백필",
            description = "기존 users/products/orders/payments 를 도메인 이벤트로 재발행해 settlement_db 프로젝션을 시드한다. 멱등.")
    @PostMapping("/backfill")
    public ResponseEntity<BackfillSettlementProjectionsUseCase.BackfillResult> backfill() {
        return ResponseEntity.ok(backfillUseCase.backfillAll());
    }
}
