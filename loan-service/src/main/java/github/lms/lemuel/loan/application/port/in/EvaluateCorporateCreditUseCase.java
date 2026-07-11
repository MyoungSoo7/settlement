package github.lms.lemuel.loan.application.port.in;

import java.math.BigDecimal;

/**
 * 상장사 신용평가·한도 조회 인바운드 포트. 대출 신청 전 CEO 가 자사 신용점수/한도를 미리 확인한다.
 */
public interface EvaluateCorporateCreditUseCase {

    CorporateCreditView evaluate(String stockCode);

    /**
     * 신용평가 결과.
     *
     * @param stockCode       종목코드
     * @param corpName        회사명
     * @param market          시장 구분
     * @param fiscalYear      평가에 사용한 재무제표 회계연도
     * @param creditScore     신용점수(0~100)
     * @param creditGrade     신용등급(A~E)
     * @param limit           승인 가능 한도
     * @param debtRatio       부채비율(%)
     * @param operatingMargin 영업이익률(%)
     * @param roa             총자산이익률(%)
     * @param reputationGrade 반영된 평판 등급(A~E, 미상이면 null)
     */
    record CorporateCreditView(
            String stockCode,
            String corpName,
            String market,
            Integer fiscalYear,
            int creditScore,
            String creditGrade,
            BigDecimal limit,
            BigDecimal debtRatio,
            BigDecimal operatingMargin,
            BigDecimal roa,
            String reputationGrade) {
    }
}
