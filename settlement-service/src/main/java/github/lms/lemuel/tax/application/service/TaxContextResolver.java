package github.lms.lemuel.tax.application.service;

import github.lms.lemuel.tax.application.dto.TaxSettlementView;
import github.lms.lemuel.tax.application.port.out.LoadSellerTaxProfilePort;
import github.lms.lemuel.tax.application.port.out.LoadSettlementForTaxPort;
import github.lms.lemuel.tax.domain.SellerTaxProfile;
import github.lms.lemuel.tax.domain.TaxCalculation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 세무 계산 컨텍스트 해석기 — 정산 뷰 + 셀러 세무 프로필을 로드해 {@link TaxCalculation} 을 조립한다.
 *
 * <p>세무 전기·세금계산서·대사 세 서비스가 동일 입력(수수료·순정산액·세무유형)을 필요로 하므로, 로드·조립을
 * 한 곳에 모아 중복을 없앤다. 미등록·미확정·미존재는 예외 대신 {@link Status} 로 표현해 호출자가 경로별로
 * (배치=보류 흡수, 관리자=보류 노출) 해석하게 한다.
 *
 * <p><b>IDOR 방지(2026-07-24, ADR 0029 후속 수정)</b>: {@code sellerId} 는 세 서비스 모두
 * {@code SettlementTaxAdminController} 의 {@code @RequestParam} 에서 그대로 넘어온다 — 이전에는 이 값을
 * 검증 없이 신뢰해 정산의 실제 소유 셀러와 무관하게 세무 프로필·세금계산서를 조립·영속했다(특히
 * {@code IssueTaxInvoiceService} 는 잘못된 sellerId 로 세금계산서를 영구 발급). 여기 단일 초크포인트에서
 * {@link TaxSettlementView#sellerId()}(실제 소유 셀러)와 요청 sellerId 를 대조해 불일치 시 즉시 거부한다 —
 * 세 서비스 모두 이 메서드를 통해서만 컨텍스트를 얻으므로 배치 경로는 없다(안전하게 예외로 처리 가능).
 * 새 {@link Status} 값을 추가하지 않는다 — {@code TaxReconciliationService} 의 switch 는 {@code default -> OK}
 * 라 새 상태를 조용히 통과시키고, 배치 없는 경로이므로 예외가 더 안전하다.
 */
@Component
public class TaxContextResolver {

    private static final Logger log = LoggerFactory.getLogger(TaxContextResolver.class);

    public enum Status {
        OK,
        SETTLEMENT_NOT_FOUND,
        NO_PROFILE,
        NOT_DONE
    }

    /**
     * @param status      해석 결과 구분
     * @param view        정산 뷰(미존재 시 null)
     * @param profile     세무 프로필(미등록 시 null)
     * @param calculation 세무 계산(OK 일 때만 non-null)
     */
    public record Resolved(Status status, TaxSettlementView view, SellerTaxProfile profile,
                           TaxCalculation calculation) {
        public boolean isOk() {
            return status == Status.OK;
        }
    }

    private final LoadSettlementForTaxPort loadSettlementPort;
    private final LoadSellerTaxProfilePort loadProfilePort;

    public TaxContextResolver(LoadSettlementForTaxPort loadSettlementPort,
                              LoadSellerTaxProfilePort loadProfilePort) {
        this.loadSettlementPort = loadSettlementPort;
        this.loadProfilePort = loadProfilePort;
    }

    public Resolved resolve(Long settlementId, Long sellerId) {
        TaxSettlementView view = loadSettlementPort.findById(settlementId).orElse(null);
        if (view == null) {
            return new Resolved(Status.SETTLEMENT_NOT_FOUND, null, null, null);
        }
        Long trueOwnerSellerId = view.sellerId();
        if (trueOwnerSellerId == null) {
            // 실제 소유 셀러를 해석하지 못함(payment→order→product→seller 매핑 실패/미할당) — 검증 불가,
            // 하드 차단 대신 경고만 남기고 진행(요청 sellerId 를 그대로 신뢰).
            log.warn("[TaxContextResolver] 정산의 실제 소유 셀러를 해석하지 못해 IDOR 소유권 검증을 건너뜁니다: "
                    + "settlementId={}, requestedSellerId={}", settlementId, sellerId);
        } else if (!trueOwnerSellerId.equals(sellerId)) {
            throw new AccessDeniedException(
                    "요청 sellerId 가 정산의 실제 소유 셀러와 일치하지 않습니다: settlementId=" + settlementId
                            + ", requestedSellerId=" + sellerId + ", ownerSellerId=" + trueOwnerSellerId);
        }
        SellerTaxProfile profile = loadProfilePort.findBySellerId(sellerId).orElse(null);
        if (profile == null) {
            return new Resolved(Status.NO_PROFILE, view, null, null);
        }
        if (!view.isDone()) {
            return new Resolved(Status.NOT_DONE, view, profile, null);
        }
        TaxCalculation calc = TaxCalculation.of(view.commission(), view.netAmount(), profile.getTaxType());
        return new Resolved(Status.OK, view, profile, calc);
    }
}
