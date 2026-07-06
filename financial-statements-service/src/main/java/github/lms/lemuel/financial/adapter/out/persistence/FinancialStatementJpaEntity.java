package github.lms.lemuel.financial.adapter.out.persistence;

import github.lms.lemuel.financial.domain.FinancialStatement;
import github.lms.lemuel.financial.domain.FsDivision;
import github.lms.lemuel.financial.domain.StatementSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "financial_statements",
        uniqueConstraints = @UniqueConstraint(name = "uq_fs_company_year_div",
                columnNames = {"stock_code", "fiscal_year", "fs_div"}))
public class FinancialStatementJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 6)
    private String stockCode;

    @Column(name = "fiscal_year", nullable = false)
    private int fiscalYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "fs_div", nullable = false, length = 3)
    private FsDivision fsDiv;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "revenue", precision = 21)
    private BigDecimal revenue;

    @Column(name = "operating_profit", precision = 21)
    private BigDecimal operatingProfit;

    @Column(name = "net_income", precision = 21)
    private BigDecimal netIncome;

    @Column(name = "total_assets", precision = 21)
    private BigDecimal totalAssets;

    @Column(name = "total_liabilities", precision = 21)
    private BigDecimal totalLiabilities;

    @Column(name = "total_equity", precision = 21)
    private BigDecimal totalEquity;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10)
    private StatementSource source;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    protected FinancialStatementJpaEntity() {
    }

    static FinancialStatementJpaEntity fromDomain(FinancialStatement statement) {
        FinancialStatementJpaEntity entity = new FinancialStatementJpaEntity();
        entity.stockCode = statement.stockCode();
        entity.fiscalYear = statement.fiscalYear();
        entity.fsDiv = statement.fsDivision();
        entity.applyDomain(statement);
        return entity;
    }

    void applyDomain(FinancialStatement statement) {
        this.currency = statement.currency();
        this.revenue = statement.revenue();
        this.operatingProfit = statement.operatingProfit();
        this.netIncome = statement.netIncome();
        this.totalAssets = statement.totalAssets();
        this.totalLiabilities = statement.totalLiabilities();
        this.totalEquity = statement.totalEquity();
        this.source = statement.source();
        this.syncedAt = statement.syncedAt();
    }

    FinancialStatement toDomain() {
        return new FinancialStatement(id, stockCode, fiscalYear, fsDiv, currency,
                revenue, operatingProfit, netIncome, totalAssets, totalLiabilities, totalEquity,
                source, syncedAt);
    }
}
