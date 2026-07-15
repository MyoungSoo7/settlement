package github.lms.lemuel.company.application.port.in;

import java.util.List;

/**
 * 기업 마스터 일괄 등록(upsert) — 외부 파이프라인(예: trusted-ceo-agent 유니버스 빌더)이
 * 코스피·코스닥 상장사 전체를 문서함 업로드 대상으로 미리 등록하기 위한 운영 진입점.
 *
 * <p>Phase 1 은 시드가 유일한 적재원이었으나, 상장사 전체를 대상으로 브리핑을 돌리려면
 * 업로드 전에 대상 기업이 등록돼 있어야 한다(미등록 stockCode 는 업로드가 404). 같은
 * stockCode 재등록은 교체(upsert) — 재실행 멱등.
 */
public interface RegisterCompaniesUseCase {

    RegisterResult register(List<RegisterCommand> companies);

    /** market 이 비면 도메인이 KOSPI 로 기본값 처리, corpCode 는 8자리 또는 공란(미상). */
    record RegisterCommand(String stockCode, String corpCode, String name, String market) {
    }

    /**
     * @param received   요청 항목 수
     * @param registered 신규 등록 수
     * @param updated    기존 갱신 수
     * @param skipped    제약 위반 등으로 건너뛴 수(corpCode UNIQUE 충돌 등)
     */
    record RegisterResult(int received, int registered, int updated, int skipped) {
    }
}
