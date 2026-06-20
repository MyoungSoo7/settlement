package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.domain.Settlement;

/**
 * 정산서 PDF 렌더링 Outbound Port
 *
 * <p>구현체는 adapter/out/pdf 패키지에 위치한다.
 * 현재 구현체: SettlementPdfAdapter (iText 8 AGPL)
 */
public interface SettlementPdfPort {

    /**
     * Settlement 도메인 객체를 받아 PDF 바이트 배열로 렌더링한다.
     *
     * @param settlement 렌더링할 정산 도메인 객체
     * @return PDF 바이트 배열
     */
    byte[] render(Settlement settlement);
}