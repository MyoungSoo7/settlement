package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;

import java.time.LocalDate;

/**
 * company-service 가 발행한 기업 평판 등급의 로컬 프로젝션 (ADR 0023 Phase 3).
 *
 * <p>loan-service 는 company_db 를 직접 읽을 수 없으므로(DB-per-service), 셀러(법인)의 평판
 * 리스크 신호를 {@code lemuel.company.reputation_changed} 이벤트로 받아 자체 DB 에 materialize 한다.
 * 종목코드가 식별자라 재수신 시 멱등 UPSERT 된다.
 *
 * <p>순수 POJO — 등급은 company 의 enum 을 코드 의존 없이 문자열(A~E)로만 보관한다(MSA 경계).
 */
public class CompanyReputation {

    private final String stockCode;
    private final int score;
    private final String grade;
    private final String previousGrade;
    private final LocalDate snapshotDate;

    /** 평판 스냅샷 생성 — 불변식을 검증한 뒤에만 인스턴스가 존재한다(도메인 팩토리 정합). */
    public static CompanyReputation of(String stockCode, int score, String grade,
                                       String previousGrade, LocalDate snapshotDate) {
        return new CompanyReputation(stockCode, score, grade, previousGrade, snapshotDate);
    }

    private CompanyReputation(String stockCode, int score, String grade,
                              String previousGrade, LocalDate snapshotDate) {
        if (stockCode == null || stockCode.length() != 6) {
            throw new LoanInvariantViolationException("종목코드는 6자리여야 합니다: " + stockCode);
        }
        if (score < 0 || score > 100) {
            throw new LoanInvariantViolationException("점수는 0~100 이어야 합니다: " + score);
        }
        if (grade == null || grade.isBlank()) {
            throw new LoanInvariantViolationException("등급은 필수입니다");
        }
        if (snapshotDate == null) {
            throw new LoanInvariantViolationException("스냅샷 일자는 필수입니다");
        }
        this.stockCode = stockCode;
        this.score = score;
        this.grade = grade;
        this.previousGrade = previousGrade;
        this.snapshotDate = snapshotDate;
    }

    public String getStockCode() {
        return stockCode;
    }

    public int getScore() {
        return score;
    }

    public String getGrade() {
        return grade;
    }

    public String getPreviousGrade() {
        return previousGrade;
    }

    public LocalDate getSnapshotDate() {
        return snapshotDate;
    }
}
