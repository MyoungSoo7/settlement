package github.lms.lemuel.commondata.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataSourceTest {

    private static DataSource source(String code, String name, String endpoint,
                                     Map<String, String> params, List<String> keyFields, int pageSize) {
        return new DataSource(null, code, name, endpoint, params, keyFields, pageSize, true, null, null);
    }

    @Test
    @DisplayName("정상 생성 — 파라미터/키필드 보존")
    void createsWithValues() {
        DataSource source = source("kasi-rest-days", "특일정보",
                "https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo",
                Map.of("_type", "json"), List.of("locdate", "seq"), 50);

        assertEquals("kasi-rest-days", source.code());
        assertEquals(Map.of("_type", "json"), source.defaultParams());
        assertEquals(List.of("locdate", "seq"), source.keyFields());
        assertEquals(50, source.pageSize());
        assertTrue(source.enabled());
    }

    @Test
    @DisplayName("defaultParams/keyFields null 은 빈 컬렉션으로 정규화")
    void normalizesNullCollections() {
        DataSource source = source("src-1", "이름", "https://apis.data.go.kr/x", null, null, 100);

        assertEquals(Map.of(), source.defaultParams());
        assertEquals(List.of(), source.keyFields());
    }

    @Test
    @DisplayName("keyFields 공백 항목은 제거, 양끝 공백은 strip")
    void normalizesKeyFields() {
        List<String> fields = new ArrayList<>();
        fields.add(" locdate ");
        fields.add("");
        fields.add(null);
        fields.add("seq");

        DataSource source = source("src-1", "이름", "https://apis.data.go.kr/x", null, fields, 100);

        assertEquals(List.of("locdate", "seq"), source.keyFields());
    }

    @Test
    @DisplayName("pageSize 0 이하는 기본값, 상한 초과는 상한으로 보정")
    void clampsPageSize() {
        assertEquals(DataSource.DEFAULT_PAGE_SIZE,
                source("src-1", "이름", "https://apis.data.go.kr/x", null, null, 0).pageSize());
        assertEquals(DataSource.MAX_PAGE_SIZE,
                source("src-1", "이름", "https://apis.data.go.kr/x", null, null, 99999).pageSize());
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "UPPER", "한글", "has space", "a", "-lead", "too_underscore"})
    @DisplayName("code 는 소문자·숫자·하이픈 2~50자 패턴을 강제")
    void rejectsInvalidCode(String code) {
        assertThrows(IllegalArgumentException.class,
                () -> source(code, "이름", "https://apis.data.go.kr/x", null, null, 100));
    }

    @Test
    @DisplayName("code null 거부")
    void rejectsNullCode() {
        assertThrows(IllegalArgumentException.class,
                () -> source(null, "이름", "https://apis.data.go.kr/x", null, null, 100));
    }

    @Test
    @DisplayName("name 누락 거부")
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> source("src-1", " ", "https://apis.data.go.kr/x", null, null, 100));
        assertThrows(IllegalArgumentException.class,
                () -> source("src-1", null, "https://apis.data.go.kr/x", null, null, 100));
    }

    @Test
    @DisplayName("endpoint 는 http(s) URL 만 허용")
    void rejectsInvalidEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> source("src-1", "이름", null, null, null, 100));
        assertThrows(IllegalArgumentException.class,
                () -> source("src-1", "이름", "ftp://apis.data.go.kr/x", null, null, 100));
    }
}
