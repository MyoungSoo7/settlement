package github.lms.lemuel.insurance.application.port.in;

import github.lms.lemuel.insurance.domain.DisclosureDelivery;
import github.lms.lemuel.insurance.domain.SalesChannel;

/**
 * 상품설명서 교부 기록 유스케이스 — 완전판매 증빙.
 *
 * <p>서버가 직접 렌더링한 문서의 SHA-256 을 기록한다 — 클라이언트가 보낸 해시를 신뢰하지
 * 않는다(증빙 위조 차단). 기록은 append-only (V7 트리거가 DB 레벨에서 강제).
 */
public interface RecordDisclosureDeliveryUseCase {

    /**
     * 교부 = 문서 발급 + 증빙 기록의 단일 행위 — 반환된 PDF 가 곧 교부된 문서이고,
     * 기록된 SHA-256 은 정확히 그 바이트의 해시다 (재렌더링 불일치 없음).
     */
    DeliveredDisclosure record(RecordDeliveryCommand command);

    /**
     * @param delivery 저장된 교부 증빙
     * @param pdf      교부된 상품설명서 PDF — delivery.documentSha256 와 해시 일치
     */
    record DeliveredDisclosure(DisclosureDelivery delivery, byte[] pdf) {
    }

    /**
     * @param applicationId   청약 경유 교부 시 청약 ID (사전 상담 교부는 null)
     * @param productCode     교부한 상품
     * @param salesChannel    교부 채널 (FC | BANCA)
     * @param deliveredBy     교부자 — FC id 또는 은행 창구 직원 id
     * @param partnerBankCode BANCA 교부 시 판매 은행 (FC 면 null)
     * @param contractorName  교부 상대(계약자) 이름
     */
    record RecordDeliveryCommand(String applicationId, String productCode, SalesChannel salesChannel,
                                 String deliveredBy, String partnerBankCode, String contractorName) {
    }
}
