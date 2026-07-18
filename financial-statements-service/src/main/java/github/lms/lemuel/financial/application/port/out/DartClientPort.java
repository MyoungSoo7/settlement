package github.lms.lemuel.financial.application.port.out;

import github.lms.lemuel.financial.domain.FsDivision;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 금감원 OpenDART 연동 아웃바운드 포트.
 *
 * <p>구현체는 adapter/out/external/DartApiClient. 시장 구분 판별(corp_cls: Y=유가/K=코스닥)과
 * 요약계정 파싱은 어댑터가 흡수하고, 애플리케이션 계층은 이 포트의 시장 중립 모델만 본다.
 */
public interface DartClientPort {

    /** API 키가 설정돼 있어 실호출이 가능한지. */
    boolean isConfigured();

    /** corpCode.xml — 종목코드를 보유한(=상장) 기업 전체. 코스피/코스닥 미구분. */
    List<ListedCompany> fetchListedCompanies();

    /** company.json 기업개황 — 시장 구분(corpClass: Y=유가/K=코스닥) 포함. 미존재 시 empty. */
    Optional<CompanyProfile> fetchProfile(String corpCode);

    /** fnlttSinglAcnt.json 사업보고서(연간) 주요계정 — 연결(CFS) 우선, 없으면 별도(OFS). 미공시 시 empty. */
    Optional<AnnualSummary> fetchAnnualSummary(String corpCode, int year);

    record ListedCompany(String corpCode, String stockCode, String name) {
    }

    record CompanyProfile(String corpCode, String corpClass, String name) {
        /** corp_cls → 수집 대상 시장명 매핑. Y=KOSPI, K=KOSDAQ, 그 외(N=코넥스/E=기타)는 null(비수집). */
        public String marketOrNull() {
            if ("Y".equals(corpClass)) {
                return "KOSPI";
            }
            if ("K".equals(corpClass)) {
                return "KOSDAQ";
            }
            return null;
        }
    }

    record AnnualSummary(FsDivision fsDivision, String currency, BigDecimal revenue,
                         BigDecimal operatingProfit, BigDecimal netIncome, BigDecimal totalAssets,
                         BigDecimal totalLiabilities, BigDecimal totalEquity) {
    }
}
