package github.lms.lemuel.company.application.port.in;

/**
 * 셀러(회원)를 기업(종목코드)에 명시적으로 링크하는 인바운드 포트 (운영자 전용).
 * user.registered 에 기업 연결 키가 없어 자동 매핑이 불가능하므로 링크는 명시적이다.
 */
public interface LinkSellerUseCase {

    void link(Long sellerId, String stockCode);
}
