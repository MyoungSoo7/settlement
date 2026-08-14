package github.lms.lemuel.investment.adapter.out.external;

import github.lms.lemuel.investment.domain.exception.InvestmentInvariantViolationException;

import java.util.regex.Pattern;

/**
 * 위성 서비스(company·market·financial) URL 경로에 종목코드를 끼우기 전에 통과시키는 관문.
 *
 * <p>종목코드는 {@code GET /api/investment/checks/{stockCode}} 처럼 <b>요청에서 그대로 온 값</b>이다.
 * 이걸 검증 없이 경로에 이어 붙이면 {@code ../../admin/...} 같은 입력이 내부 서비스의 다른 경로를
 * 호출하는 통로가 된다(경로 조작 → 사실상 서버측 요청 위조). 영숫자만 허용해 경로 구분자·인코딩
 * 우회 문자를 원천 차단한다 — KRX 종목코드는 6자리 숫자이며 우선주 접미가 붙어도 영숫자다.
 *
 * <p>호출부는 이 관문을 통과한 값만 URI 템플릿 변수로 넘긴다(이중 방어 — 템플릿 변수는 인코딩까지 한다).
 */
final class StockCodePath {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9]{1,20}");

    private StockCodePath() {
    }

    /** 경로에 끼울 수 있는 종목코드만 통과시킨다. 형식 위반은 400 으로 매핑되는 도메인 예외. */
    static String segment(String stockCode) {
        if (stockCode == null || !ALLOWED.matcher(stockCode).matches()) {
            throw new InvestmentInvariantViolationException("종목코드 형식이 올바르지 않습니다.", stockCode);
        }
        return stockCode;
    }
}
