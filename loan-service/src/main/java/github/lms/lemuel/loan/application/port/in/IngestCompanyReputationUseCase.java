package github.lms.lemuel.loan.application.port.in;

import java.time.LocalDate;
import java.util.List;

/**
 * company 의 CompanyReputationChanged 이벤트를 받아 로컬 평판 프로젝션에 적재하는 인바운드 포트.
 *
 * <p>{@code sellerIds} 는 company 가 동봉한, 이 기업에 링크된 셀러들 — 셀러별 평판 프로젝션
 * (신용 haircut 용)에 함께 적재된다. 링크가 없으면 빈 리스트.
 */
public interface IngestCompanyReputationUseCase {

    void ingest(IngestCompanyReputationCommand command);

    record IngestCompanyReputationCommand(String stockCode, int score, String grade,
                                          String previousGrade, LocalDate snapshotDate,
                                          List<Long> sellerIds) {
    }
}
