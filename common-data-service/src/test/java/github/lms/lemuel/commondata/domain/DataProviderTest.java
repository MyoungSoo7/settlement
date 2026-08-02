package github.lms.lemuel.commondata.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataProviderTest {

    @Test
    @DisplayName("parse — null/blank 는 기본 DATA_GO_KR")
    void parseDefaults() {
        assertEquals(DataProvider.DATA_GO_KR, DataProvider.parse(null));
        assertEquals(DataProvider.DATA_GO_KR, DataProvider.parse(" "));
    }

    @Test
    @DisplayName("parse — 대소문자·공백 관대 파싱")
    void parseLenient() {
        assertEquals(DataProvider.SEOUL_OPENAPI, DataProvider.parse("seoul_openapi"));
        assertEquals(DataProvider.SEOUL_OPENAPI, DataProvider.parse(" SEOUL_OPENAPI "));
        assertEquals(DataProvider.DATA_GO_KR, DataProvider.parse("data_go_kr"));
    }

    @Test
    @DisplayName("parse — 미지원 값은 IllegalArgumentException(400 유도)")
    void parseRejectsUnknown() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DataProvider.parse("gyeonggi"));
        assertTrue(e.getMessage().contains("provider"));
    }
}
