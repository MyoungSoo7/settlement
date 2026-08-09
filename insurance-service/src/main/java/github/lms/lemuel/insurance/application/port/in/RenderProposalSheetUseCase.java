package github.lms.lemuel.insurance.application.port.in;

/**
 * 가입설계서 PDF 렌더링 — 산출 스냅샷(피보험자·보험나이·적용요율·보험료·유효기한) 기반.
 *
 * <p>설계서에는 피보험자 이름·보장금액이 실리므로 조회와 동일하게 소유권을 대조한다.
 */
public interface RenderProposalSheetUseCase {

    /** @param requesterFcId 요청자 — JWT 주체에서 파생된 FC 식별자 */
    byte[] render(String proposalId, String requesterFcId);
}
