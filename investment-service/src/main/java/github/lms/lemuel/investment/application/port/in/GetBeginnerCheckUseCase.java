package github.lms.lemuel.investment.application.port.in;

import github.lms.lemuel.investment.domain.BeginnerInvestmentCheck;

import java.math.BigDecimal;

/** 초보 투자 체크 조회 — 투자점수 + 악재 뉴스(R3) + 시세 위치(R4·R5) + 거시 + 매매계획. */
public interface GetBeginnerCheckUseCase {

    /**
     * @param stockCode 6자리 종목코드
     * @param budget    총 예산(원) — null 허용. 있으면 매매계획에 분할 수량까지 계산한다.
     * @throws github.lms.lemuel.investment.application.exception.InvestmentNotFoundException
     *         회계자료(투자점수 앵커)가 없을 때 — 기존 점수 조회와 동일 규약(404)
     * @throws IllegalArgumentException budget 이 0 이하일 때(400)
     */
    BeginnerInvestmentCheck getCheck(String stockCode, BigDecimal budget);
}
