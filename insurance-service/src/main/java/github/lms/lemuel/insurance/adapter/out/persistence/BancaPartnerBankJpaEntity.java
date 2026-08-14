package github.lms.lemuel.insurance.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * banca_partner_banks 테이블 매핑 (V8) — 방카 파트너 은행 자산총액 레지스트리.
 *
 * <p>25%룰 적용 대상(자산 2조 이상) 판정 입력 조회 전용. 등록·갱신은 운영 SQL 몫
 * (상품 카탈로그와 동일 관례). 미등록 은행은 적용 대상으로 본다(fail-closed).
 */
@Entity
@Table(name = "banca_partner_banks", schema = "opslab")
public class BancaPartnerBankJpaEntity {

    @Id
    @Column(name = "bank_code", length = 32)
    private String bankCode;

    @Column(name = "bank_name", nullable = false, length = 100)
    private String bankName;

    @Column(name = "total_assets", nullable = false, precision = 20, scale = 2)
    private BigDecimal totalAssets;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    protected BancaPartnerBankJpaEntity() {
    }

    public String getBankCode() {
        return bankCode;
    }

    public BigDecimal getTotalAssets() {
        return totalAssets;
    }
}
