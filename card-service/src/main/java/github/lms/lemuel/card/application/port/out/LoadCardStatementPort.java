package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.CardStatement;
import github.lms.lemuel.card.domain.StatementStatus;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/** 청구서 조회 포트. */
public interface LoadCardStatementPort {

    /** ID 조회. */
    Optional<CardStatement> findById(Long id);

    /** 카드계정·청구주기 조회 — 계정당 청구주기별 1개. */
    Optional<CardStatement> findByCardAccountAndPeriod(Long cardAccountId, YearMonth billingYearMonth);

    /** 지정 청구주기의 OPEN 명세서 전체 — 마감 배치 전용. */
    List<CardStatement> findOpenByBillingYearMonth(YearMonth billingYearMonth);

    /**
     * 만기일 경과 + 미납 명세서 목록 — 연체 배치 전용.
     *
     * <p>status 가 CLOSED 또는 PARTIALLY_PAID 이면서 dueDate 가 {@code today} 이전인 행을 반환한다.
     */
    List<CardStatement> findOverdueAndUnpaid(LocalDate today);

    /**
     * 명세서 납부 멱등 체크 — 이미 처리된 paymentId 여부.
     *
     * @return 이미 처리됐으면 true
     */
    boolean existsPaymentById(String paymentId);
}
