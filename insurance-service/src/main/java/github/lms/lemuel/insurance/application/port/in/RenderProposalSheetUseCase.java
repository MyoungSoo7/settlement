package github.lms.lemuel.insurance.application.port.in;

/**
 * 가입설계서 PDF 렌더링 — 산출 스냅샷(피보험자·보험나이·적용요율·보험료·유효기한) 기반.
 */
public interface RenderProposalSheetUseCase {

    byte[] render(String proposalId);
}
