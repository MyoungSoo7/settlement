package github.lms.lemuel.payout.adapter.in.web;

import github.lms.lemuel.payout.application.port.in.ReencryptPayoutPiiUseCase;
import github.lms.lemuel.payout.domain.PayoutPiiBackfillReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지급계좌 PII 재암호화 백필 운영자 콘솔.
 *
 * <p>인가: 경로가 {@code /admin/payouts/**} 아래라 shared-common SecurityConfig 의
 * {@code .requestMatchers("/admin/payouts/**").hasRole("ADMIN")} 게이트를 상속한다 — 형제
 * PayoutAdminController(송금 실행)와 동일하게 ADMIN 전용. PII 를 다루는 변경 작업이라 조회형
 * integrity 콘솔(ADMIN/MANAGER)보다 엄격한 ADMIN 게이트를 의도적으로 재사용한다.
 *
 * <p><b>운영 절차</b>: (1) GET /status 로 평문 잔존 건수를 확인 → (2) POST /reencrypt 로 백필 실행
 * (페이지 단위 커밋, 재실행 안전 — idempotent, 이미 암호문인 행은 스킵) → (3) 응답 {@code complete}/
 * {@code remainingPlaintext} 로 완료 판정, 잔존이 남으면 재실행. {@code PAYOUT_ENC_KEY} 미설정이면
 * 서비스 부팅 자체가 실패하므로 이 API 도 노출되지 않는다.
 */
@Tag(name = "Payout PII Admin", description = "지급계좌 PII 재암호화 백필 — 레거시 평문 잔존 청소 (ADMIN)")
@RestController
@RequestMapping("/admin/payouts/pii")
public class PayoutPiiBackfillAdminController {

    private final ReencryptPayoutPiiUseCase useCase;

    public PayoutPiiBackfillAdminController(ReencryptPayoutPiiUseCase useCase) {
        this.useCase = useCase;
    }

    @Operation(summary = "평문 잔존 검증 — 재암호화 없이 평문 잔존 건수만 조회",
            description = "백필 실행 전/후 검증용. remainingPlaintext=0 이면 전량 암호문")
    @GetMapping("/status")
    public PayoutPiiBackfillReport status() {
        return useCase.remainingPlaintextCount();
    }

    @Operation(summary = "PII 재암호화 백필 실행 — 레거시 평문 행을 페이지 단위로 암호문 전환",
            description = "페이지 단위 커밋으로 대량 안전. 재실행 idempotent(암호문 행 스킵). "
                    + "응답에 backfilled·remainingPlaintext·complete 포함")
    @PostMapping("/reencrypt")
    public PayoutPiiBackfillReport reencrypt(@RequestParam(required = false) Integer pageSize) {
        return useCase.reencryptLegacyPlaintext(pageSize);
    }
}
