package github.lms.lemuel.point.adapter.in.web;

import github.lms.lemuel.point.application.port.in.ExpirePointLotsUseCase;
import github.lms.lemuel.point.application.port.in.ExpirePointLotsUseCase.ExpirePointCommand;
import github.lms.lemuel.point.application.port.in.ExpirePointLotsUseCase.ExpirePointResult;
import github.lms.lemuel.point.application.port.in.GrantPointUseCase;
import github.lms.lemuel.point.application.port.in.GrantPointUseCase.GrantPointCommand;
import github.lms.lemuel.point.application.port.in.GrantPointUseCase.GrantPointResult;
import github.lms.lemuel.point.domain.PointLotOrigin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 관리자 포인트 콘솔.
 *
 * <pre>
 *   POST /admin/points/grants           → 수기 지급(사유 필수)
 *   POST /admin/points/expiry/run       → 소멸 미리보기(무변경)
 *   POST /admin/points/expiry/run?dryRun=false → 실제 소멸 실행
 * </pre>
 *
 * <p>고객 재산을 지우는 배치라 <b>미리보기가 기본값</b>이다 — 파라미터를 빠뜨린 호출이 실행이 되어선
 * 안 된다({@code /admin/payment-expiry} 와 같은 규약).
 *
 * <p>수기 지급은 <b>사유(memo)를 필수</b>로 받는다. 근거 없이 포인트가 생기면 나중에 "왜 이 돈이
 * 여기 있나"에 답할 수 없고, 그 순간 원장은 설명력을 잃는다.
 *
 * <p>권한은 SecurityConfig 의 {@code /admin/points/**} 매처(ADMIN)로 제한된다. 이 설정에는
 * 포괄 {@code /admin/**} 매처가 <b>없다</b> — 경로별 열거 방식이라, 명시하지 않으면
 * {@code anyRequest().authenticated()} 로 새어 일반 사용자도 호출할 수 있다.
 */
@Tag(name = "Admin Point", description = "포인트 수기 지급·소멸 운영")
@RestController
@RequestMapping("/admin/points")
public class AdminPointController {

    private final GrantPointUseCase grantPointUseCase;
    private final ExpirePointLotsUseCase expirePointLotsUseCase;

    public AdminPointController(GrantPointUseCase grantPointUseCase,
                                ExpirePointLotsUseCase expirePointLotsUseCase) {
        this.grantPointUseCase = grantPointUseCase;
        this.expirePointLotsUseCase = expirePointLotsUseCase;
    }

    @Operation(summary = "포인트 수기 지급",
            description = "CS 보상 등으로 운영자가 직접 지급한다. 사유는 필수이며 원장 메모로 보존된다.")
    @PostMapping("/grants")
    public ResponseEntity<GrantPointResult> grant(@Valid @RequestBody ManualGrantRequest request) {
        OffsetDateTime expiresAt = request.validityDays() == null
                ? null
                : OffsetDateTime.now().plusDays(request.validityDays());
        GrantPointResult result = grantPointUseCase.grant(new GrantPointCommand(
                request.userId(), request.amount(), PointLotOrigin.MANUAL_GRANT,
                "MANUAL", request.referenceId(), expiresAt, actor(), request.reason()));
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "포인트 소멸 실행",
            description = "기본은 미리보기(dryRun=true). 실제 소멸은 dryRun=false 를 명시해야 한다.")
    @PostMapping("/expiry/run")
    public ResponseEntity<ExpirePointResult> runExpiry(
            @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun,
            @RequestParam(name = "batchSize", defaultValue = "500") int batchSize) {
        return ResponseEntity.ok(expirePointLotsUseCase.expire(
                new ExpirePointCommand(OffsetDateTime.now(), batchSize, dryRun, actor())));
    }

    /** 감사 주체 — 누가 지급·소멸을 실행했는지 원장에 남긴다. */
    private static String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "admin" : "admin:" + authentication.getName();
    }

    /**
     * @param referenceId  멱등 키 — 같은 값으로 두 번 호출해도 한 번만 지급된다(원장 자연키)
     * @param validityDays null 이면 무기한
     */
    public record ManualGrantRequest(
            @NotNull Long userId,
            @NotNull @Positive BigDecimal amount,
            @NotBlank String referenceId,
            @NotBlank String reason,
            Integer validityDays) {
    }
}
