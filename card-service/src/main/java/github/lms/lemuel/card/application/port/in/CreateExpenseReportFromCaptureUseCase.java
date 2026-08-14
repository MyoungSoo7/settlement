package github.lms.lemuel.card.application.port.in;

import github.lms.lemuel.card.domain.ExpenseReport;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 매입 확정 이벤트 소비로 지출보고서(DRAFT)를 자동 생성하는 유스케이스 포트.
 *
 * <p>멱등 키: {@code captureId} — 동일 매입에 대해 재요청 시 기존 보고서를 반환하고
 * 새로 생성하지 않는다.
 *
 * <p>Kafka 컨슈머({@code CardCapturedExpenseConsumer})가 {@code lemuel.card.captured} 이벤트를
 * 수신하면 이 유스케이스를 호출해 DRAFT 상태의 지출보고서를 자동 생성한다.
 */
public interface CreateExpenseReportFromCaptureUseCase {

    ExpenseReport createFromCapture(CreateExpenseReportCommand command);

    /**
     * 지출보고서 생성 커맨드.
     *
     * @param captureId       VAN 매입번호 — 멱등 자연키
     * @param authorizationId 매입 대상 승인번호
     * @param cardId          카드 ID
     * @param cardAccountId   카드계정 ID
     * @param organizationId  조직 ID
     * @param departmentId    부서 ID (예산 소진율 집계 기준)
     * @param holderUserId    카드 소지자 userId
     * @param amount          매입 금액
     * @param merchantName    가맹점 이름(optional)
     * @param capturedAt      매입 확정 시각
     */
    record CreateExpenseReportCommand(
            String captureId,
            String authorizationId,
            Long cardId,
            Long cardAccountId,
            Long organizationId,
            String departmentId,
            Long holderUserId,
            BigDecimal amount,
            String merchantName,
            Instant capturedAt
    ) {
    }
}
