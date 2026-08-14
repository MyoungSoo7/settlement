package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.domain.RateScope;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 요율 정책 <b>운영 목록</b> 조회 포트 (ADR 0032).
 *
 * <p>{@link LoadCommissionRatePolicyPort} 와 목적이 다르다 — 그쪽은 "이 셀러에게 지금 어떤 요율인가"를
 * 푸는 <b>해석용</b>이라 {@code at} 이 필수이고 등급이 NORMAL 로 접힌다. 운영자는 반대로 "무슨 정책이
 * 깔려 있나"를 통째로 봐야 하고, 무엇보다 조기 종료(close)에 필요한 <b>id 를 눈으로 확인할 수단</b>이
 * 있어야 한다.
 *
 * <p>도메인 레코드 대신 별도 행 타입을 쓰는 이유: {@code reason}·{@code createdBy} 는 "왜 이 요율인가"를
 * 설명하는 감사 근거인데 도메인 계산에는 쓰이지 않아 {@code CommissionRatePolicy} 에 없다. 운영 화면은
 * 그 근거가 핵심이라 저장 계층의 값을 그대로 실어 나른다.
 */
public interface ListCommissionRatePoliciesPort {

    /**
     * 정책 목록 — scope · scopeKey · 발효일 역순.
     *
     * @param includeClosed 종료된 정책까지 포함할지. 기본은 제외 — 지금 무엇이 살아 있는지가 먼저다.
     */
    List<PolicyRow> findRows(boolean includeClosed);

    /** 운영 목록 한 행 — 도메인 값 + 감사 근거(reason·createdBy·closedAt). */
    record PolicyRow(Long id, RateScope scope, String scopeKey, BigDecimal rate,
                     LocalDate effectiveFrom, LocalDate effectiveTo,
                     String reason, String createdBy,
                     OffsetDateTime createdAt, OffsetDateTime closedAt) {

        /** 종료된 정책인지 — 화면이 "살아 있는 것"과 "이력"을 섞어 보여 주지 않게. */
        public boolean closed() {
            return closedAt != null;
        }
    }
}
