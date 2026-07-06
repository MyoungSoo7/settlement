package github.lms.lemuel.loan.application.port.in;

import java.time.LocalDate;

/**
 * company 의 CompanyReputationChanged 이벤트를 받아 로컬 평판 프로젝션에 적재하는 인바운드 포트.
 */
public interface IngestCompanyReputationUseCase {

    void ingest(IngestCompanyReputationCommand command);

    record IngestCompanyReputationCommand(String stockCode, int score, String grade,
                                          String previousGrade, LocalDate snapshotDate) {
    }
}
