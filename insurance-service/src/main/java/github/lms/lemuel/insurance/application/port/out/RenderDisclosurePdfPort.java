package github.lms.lemuel.insurance.application.port.out;

import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort.ProductSnapshot;

/**
 * 상품설명서 PDF 렌더링 포트 — 구현은 adapter/out/pdf (iText).
 *
 * <p>렌더링 바이트는 호출마다 다를 수 있다(PDF 문서 ID·생성시각) — 그래서 교부 증빙 해시는
 * "교부 시점에 렌더링된 바로 그 바이트"에서 계산하고, 그 바이트를 교부 응답으로 돌려준다
 * ({@code RecordDisclosureDeliveryUseCase.DeliveredDisclosure}).
 */
public interface RenderDisclosurePdfPort {

    byte[] render(ProductSnapshot product);
}
