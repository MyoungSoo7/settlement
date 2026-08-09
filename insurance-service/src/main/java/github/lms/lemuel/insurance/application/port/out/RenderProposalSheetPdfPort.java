package github.lms.lemuel.insurance.application.port.out;

import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort.ProductSnapshot;
import github.lms.lemuel.insurance.domain.ProposalQuote;

/** 가입설계서 PDF 렌더링 포트. */
public interface RenderProposalSheetPdfPort {

    byte[] render(ProposalQuote proposal, ProductSnapshot product);
}
