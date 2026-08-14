package github.lms.lemuel.settlement.application.port.in;

import java.time.LocalDate;

public interface ReleaseHoldbackUseCase {

    /**
     * 주어진 날짜 시점에 release 가능한 모든 holdback 을 일괄 해제.
     * 매일 자정 + α 에 배치로 호출 (HoldbackReleaseScheduler).
     *
     * @return 해제된 정산 건수
     */
    int releaseAllDueOn(LocalDate today);

    /**
     * 해제 없이 "오늘 무엇이 얼마나 풀릴지"만 산출한다. 아무 상태도 바꾸지 않는다.
     *
     * <p>홀드백 해제는 지급 요청과 회계 이벤트를 함께 만들어내므로, 규모를 먼저 보고 확정할 수 있어야 한다.
     *
     * @param limit 최대 조회 건수 — 실행 경로의 드레인 루프를 재사용하지 않는다(미리보기는 아무것도
     *              해제하지 않아 같은 배치가 계속 반환되기 때문)
     */
    HoldbackReleasePreview previewReleasableOn(LocalDate today, int limit);

    /**
     * @param truncated limit 까지 가득 찼는지 — true 면 이것이 전부가 아니다(운영자 오판 방지)
     */
    record HoldbackReleasePreview(int count, java.math.BigDecimal totalAmount,
                                  boolean truncated, java.util.List<ReleasableLine> lines) { }

    /** 해제 예정 1건. */
    record ReleasableLine(Long settlementId, Long paymentId, java.math.BigDecimal holdbackAmount,
                          LocalDate releaseDate) { }
}
