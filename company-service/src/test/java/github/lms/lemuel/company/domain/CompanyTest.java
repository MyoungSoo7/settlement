package github.lms.lemuel.company.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanyTest {

    @Test
    @DisplayName("종목코드는 6자리가 아니면 거부한다")
    void rejectsInvalidStockCode() {
        assertThrows(IllegalArgumentException.class, () -> new Company("12345", null, "삼성전자", null));
        assertThrows(IllegalArgumentException.class, () -> new Company(null, null, "삼성전자", null));
    }

    @Test
    @DisplayName("corpCode 는 nullable 이지만 있으면 8자리여야 한다")
    void rejectsInvalidCorpCode() {
        assertThrows(IllegalArgumentException.class, () -> new Company("005930", "1234567", "삼성전자", null));
        assertNull(new Company("005930", null, "삼성전자", null).corpCode());
    }

    @Test
    @DisplayName("기업명 누락은 거부한다")
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> new Company("005930", null, " ", null));
    }

    @Test
    @DisplayName("market 미지정 시 KOSPI 기본값")
    void defaultsMarketToKospi() {
        assertEquals("KOSPI", new Company("005930", null, "삼성전자", null).market());
        assertEquals("KOSDAQ", new Company("005930", null, "삼성전자", "KOSDAQ").market());
    }

    @Test
    @DisplayName("동일성은 종목코드 기준")
    void equalityByStockCode() {
        assertEquals(new Company("005930", null, "삼성전자", null),
                new Company("005930", "12345678", "다른이름", "KOSDAQ"));
    }
}
