package github.lms.lemuel.settlement.application.port.in;

/**
 * 정산서 PDF 생성 UseCase (Inbound Port)
 *
 * <p>정산 ID를 받아 PDF 바이트 배열을 반환한다.
 * Controller에서 application/pdf 응답으로 변환해 클라이언트에 전달한다.
 */
public interface GenerateSettlementPdfUseCase {

    /**
     * @param settlementId 정산 ID
     * @return PDF 바이트 배열
     */
    byte[] generate(Long settlementId);
}