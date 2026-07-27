package github.lms.lemuel.company.application.port.in;

import java.nio.file.Path;

/**
 * 국민연금공단_국민연금 가입 사업장 내역 CSV(월간 배포)를 사업자 전용으로 적재한다.
 * 같은 (사업장명, 사업자등록번호앞6자리, 자료생성년월) 재적재는 갱신(멱등).
 */
public interface ImportCompanyWorkforceUseCase {

    ImportResult importFrom(Path csvPath);

    /**
     * @param received CSV 데이터 행 수(헤더 제외)
     * @param imported 적재(신규+갱신) 건수
     * @param skipped  탈퇴 사업장·사업장명 공백·파싱 실패로 건너뛴 건수
     */
    record ImportResult(long received, long imported, long skipped) {
    }
}
