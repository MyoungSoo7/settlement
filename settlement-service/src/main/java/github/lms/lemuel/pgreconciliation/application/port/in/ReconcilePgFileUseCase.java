package github.lms.lemuel.pgreconciliation.application.port.in;

import github.lms.lemuel.pgreconciliation.domain.ReconciliationRun;

import java.io.InputStream;
import java.time.LocalDate;

public interface ReconcilePgFileUseCase {
    /**
     * PG 정산 파일 1건 업로드 → 파싱 → 내부 원장과 비교 → 결과 저장.
     *
     * @param pgProvider TOSS / KCP / NICE / INICIS
     * @param targetDate 영업일
     * @param fileName   원본 파일명 (감사 추적용)
     * @param input      파일 InputStream
     * @param operatorId 업로드한 운영자 ID
     */
    ReconciliationRun reconcile(String pgProvider, LocalDate targetDate, String fileName,
                                InputStream input, String operatorId);
}
