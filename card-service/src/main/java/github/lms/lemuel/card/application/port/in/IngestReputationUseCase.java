package github.lms.lemuel.card.application.port.in;

import java.util.List;

/**
 * company.reputation_changed 이벤트를 셀러별 평판 프로젝션으로 적재하는 인바운드 포트.
 *
 * <p>이벤트는 기업(stockCode) 단위 스냅샷이고, 그 기업에 링크된 {@code sellerIds}(정수 배열)만
 * 동봉한다 — 단일 {@code sellerId} 필드는 없다. loan-service 의 동일 이벤트 소비 선례
 * (CompanyReputationService.ingest → SaveSellerReputationPort.upsert 팬아웃, ADR 0023 Phase 3)를
 * 따라 배열의 각 원소를 개별 {@code reputation_projection} 행으로 UPSERT 한다.
 */
public interface IngestReputationUseCase {

    void ingest(ReputationCommand command);

    record ReputationCommand(List<Long> sellerIds, String grade) {
    }
}
