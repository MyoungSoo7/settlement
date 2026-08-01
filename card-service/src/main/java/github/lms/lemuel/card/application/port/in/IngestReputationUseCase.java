package github.lms.lemuel.card.application.port.in;

import java.util.List;

/**
 * company-service 평판 등급 이벤트를 셀러별 평판 프로젝션으로 적재하는 유스케이스.
 *
 * <p>이벤트는 기업(stockCode) 단위지만 카드 한도 산정은 셀러 단위이므로,
 * 페이로드의 {@code sellerIds} 를 팬아웃해 셀러별 등급으로 저장한다.
 */
public interface IngestReputationUseCase {

    void ingest(ReputationCommand command);

    record ReputationCommand(String grade, List<Long> sellerIds) { }
}
