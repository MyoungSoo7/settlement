package github.lms.lemuel.tax.application.service;

import github.lms.lemuel.tax.application.dto.TaxSettlementView;
import github.lms.lemuel.tax.application.port.out.LoadSellerTaxProfilePort;
import github.lms.lemuel.tax.application.port.out.LoadSettlementForTaxPort;
import github.lms.lemuel.tax.domain.SellerTaxProfile;
import github.lms.lemuel.tax.domain.TaxCalculation;
import org.springframework.stereotype.Component;

/**
 * 세무 계산 컨텍스트 해석기 — 정산 뷰 + 셀러 세무 프로필을 로드해 {@link TaxCalculation} 을 조립한다.
 *
 * <p>세무 전기·세금계산서·대사 세 서비스가 동일 입력(수수료·순정산액·세무유형)을 필요로 하므로, 로드·조립을
 * 한 곳에 모아 중복을 없앤다. 미등록·미확정·미존재는 예외 대신 {@link Status} 로 표현해 호출자가 경로별로
 * (배치=보류 흡수, 관리자=보류 노출) 해석하게 한다.
 */
@Component
public class TaxContextResolver {

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
