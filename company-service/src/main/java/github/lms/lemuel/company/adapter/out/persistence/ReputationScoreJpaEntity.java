package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.domain.IssueCategory;
import github.lms.lemuel.company.domain.ReputationGrade;
import github.lms.lemuel.company.domain.ReputationScore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

/**
 * 평판 스냅샷 엔티티 — INSERT-only. 카테고리별 부정 건수는 조회·설명 편의를 위해 5개 컬럼으로 편다
 * (JSONB 대신 명시 컬럼 — validate 친화적이고 SQL 로 바로 집계 가능).
 */
@Entity
@Table(name = "reputation_scores")
public class ReputationScoreJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 6)
    private String stockCode;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "score", nullable = false)
    private int score;

    @Column(name = "grade", nullable = false, length = 1)
    private String grade;

    @Column(name = "article_count", nullable = false)
    private int articleCount;

    @Column(name = "positive_count", nullable = false)
    private int positiveCount;

    @Column(name = "negative_count", nullable = false)
    private int negativeCount;

    @Column(name = "neutral_count", nullable = false)
    private int neutralCount;

    @Column(name = "financial_cnt", nullable = false)
    private int financialCnt;

    @Column(name = "legal_cnt", nullable = false)
    private int legalCnt;

    @Column(name = "governance_cnt", nullable = false)
    private int governanceCnt;

    @Column(name = "labor_cnt", nullable = false)
    private int laborCnt;

    @Column(name = "product_cnt", nullable = false)
    private int productCnt;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    protected ReputationScoreJpaEntity() {
    }

    static ReputationScoreJpaEntity fromDomain(ReputationScore s) {
        ReputationScoreJpaEntity e = new ReputationScoreJpaEntity();
        e.stockCode = s.stockCode();
        e.snapshotDate = s.snapshotDate();
        e.score = s.score();
        e.grade = s.grade().name();
        e.articleCount = s.articleCount();
        e.positiveCount = s.positiveCount();
        e.negativeCount = s.negativeCount();
        e.neutralCount = s.neutralCount();
        e.financialCnt = s.negativeCountOf(IssueCategory.FINANCIAL);
        e.legalCnt = s.negativeCountOf(IssueCategory.LEGAL);
        e.governanceCnt = s.negativeCountOf(IssueCategory.GOVERNANCE);
        e.laborCnt = s.negativeCountOf(IssueCategory.LABOR);
        e.productCnt = s.negativeCountOf(IssueCategory.PRODUCT);
        e.calculatedAt = s.calculatedAt();
        return e;
    }

    ReputationScore toDomain() {
        Map<IssueCategory, Integer> byCategory = new EnumMap<>(IssueCategory.class);
        putIfPositive(byCategory, IssueCategory.FINANCIAL, financialCnt);
        putIfPositive(byCategory, IssueCategory.LEGAL, legalCnt);
        putIfPositive(byCategory, IssueCategory.GOVERNANCE, governanceCnt);
        putIfPositive(byCategory, IssueCategory.LABOR, laborCnt);
        putIfPositive(byCategory, IssueCategory.PRODUCT, productCnt);
        return ReputationScore.rehydrate(stockCode, snapshotDate, score, ReputationGrade.valueOf(grade),
                articleCount, positiveCount, negativeCount, neutralCount, byCategory, calculatedAt);
    }

    private static void putIfPositive(Map<IssueCategory, Integer> map, IssueCategory category, int count) {
        if (count > 0) {
            map.put(category, count);
        }
    }
}
