package github.lms.lemuel.deposit.adapter.in.web;

import github.lms.lemuel.deposit.adapter.in.web.dto.ApplyOffsetRequest;
import github.lms.lemuel.deposit.adapter.in.web.dto.DepositEntryRequest;
import github.lms.lemuel.deposit.adapter.in.web.dto.DepositHoldResponse;
import github.lms.lemuel.deposit.adapter.in.web.dto.PlaceHoldRequest;
import github.lms.lemuel.deposit.application.port.in.ApplyOffsetUseCase;
import github.lms.lemuel.deposit.application.port.in.CreditDepositUseCase;
import github.lms.lemuel.deposit.application.port.in.DebitDepositUseCase;
import github.lms.lemuel.deposit.application.port.in.PlaceHoldUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예치금 운영 콘솔 (SPEC §3.16 {@code /admin/deposits}). ADMIN 전용 —
 * SecurityConfig 가 {@code /admin/deposits/**} 를 {@code hasRole("ADMIN")} 으로 잠근다.
 *
 * <p>★ 이 콘솔이 존재하는 이유 — 자동 경로가 아직 반쪽이기 때문이다.
 * 입금·출금은 {@code lemuel.settlement.confirmed} / {@code lemuel.payout.completed} 컨슈머가
 * 자동으로 처리한다. 반면 선점(hold)·상계(offset)의 자동 트리거인
 * {@code lemuel.card.authorized} / {@code lemuel.card.captured} 는 페이로드에 {@code sellerId} 가
 * 없고({@code cardAccountId} 만 있다) card 의 sellerId 는 String, deposit 은 Long 이라
 * 지금은 이벤트만으로 대상 계좌를 특정할 수 없다. 그 계약을 고치기 전까지 hold·offset 의
 * 유일한 입력 경로가 여기다 — SPEC §3.16 이 "현재 잔고 변동 입력은 수기 콘솔 경로뿐"이라고
 * 적어 둔 상태 그대로다.
 *
 * <p>입금·출금 엔드포인트는 자동 경로와 중복되지만 남겨 둔다: 컨슈머가 DLT 로 빠진 건을
 * 운영자가 손으로 되살릴 통로가 없으면, 원장이 어긋난 채로 복구 수단이 사라진다.
 * 중복 실행은 원장의 L3 UNIQUE 제약이 막는다.
 */
@RestController
@RequestMapping("/admin/deposits")
public class DepositAdminController {

    private final CreditDepositUseCase creditDepositUseCase;
    private final DebitDepositUseCase debitDepositUseCase;
    private final PlaceHoldUseCase placeHoldUseCase;
    private final ApplyOffsetUseCase applyOffsetUseCase;

    public DepositAdminController(CreditDepositUseCase creditDepositUseCase,
                                  DebitDepositUseCase debitDepositUseCase,
                                  PlaceHoldUseCase placeHoldUseCase,
                                  ApplyOffsetUseCase applyOffsetUseCase) {
        this.creditDepositUseCase = creditDepositUseCase;
        this.debitDepositUseCase = debitDepositUseCase;
        this.placeHoldUseCase = placeHoldUseCase;
        this.applyOffsetUseCase = applyOffsetUseCase;
    }

    /** 수기 입금. 계좌가 없으면 만들어진다(getOrCreate). */
    @PostMapping("/accounts/{sellerId}/credits")
    public ResponseEntity<Void> credit(@PathVariable Long sellerId,
                                       @Valid @RequestBody DepositEntryRequest request) {
        creditDepositUseCase.credit(sellerId, request.amount(),
                request.referenceId(), request.referenceType());
        return ResponseEntity.accepted().build();
    }

    /** 수기 출금. 계좌가 없거나 available 이 모자라면 실패한다 — 마이너스 잔고를 만들지 않는다. */
    @PostMapping("/accounts/{sellerId}/debits")
    public ResponseEntity<Void> debit(@PathVariable Long sellerId,
                                      @Valid @RequestBody DepositEntryRequest request) {
        debitDepositUseCase.debit(sellerId, request.amount(),
                request.referenceId(), request.referenceType());
        return ResponseEntity.accepted().build();
    }

    /** 수기 선점. {@code (holderType, holderReference)} 가 같으면 기존 hold 를 그대로 돌려준다(멱등). */
    @PostMapping("/accounts/{sellerId}/holds")
    public ResponseEntity<DepositHoldResponse> placeHold(@PathVariable Long sellerId,
                                                         @Valid @RequestBody PlaceHoldRequest request) {
        return ResponseEntity.ok(DepositHoldResponse.from(
                placeHoldUseCase.placeHold(sellerId, request.holderType(), request.holderReference(),
                        request.amount(), request.expiresAt())));
    }

    /**
     * 수기 상계. 잔고가 부족해도 실패가 아니라 shortfall 레코드로 기록되므로 202 를 돌려준다 —
     * 부족분 처리는 이 도메인에서 설계된 정상 결과다.
     */
    @PostMapping("/accounts/{sellerId}/offsets")
    public ResponseEntity<Void> applyOffset(@PathVariable Long sellerId,
                                            @Valid @RequestBody ApplyOffsetRequest request) {
        applyOffsetUseCase.applyOffset(sellerId, request.holderType(), request.holderReference(),
                request.offsetAmount(), request.offsetSequence(), request.occurredAt());
        return ResponseEntity.accepted().build();
    }
}
