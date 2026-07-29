package github.lms.lemuel.company.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkplaceRegionTest {

    @Test
    @DisplayName("도로명주소 앞 2토큰에서 시도+시군구를 뽑는다")
    void parsesSidoAndSigungu() {
        WorkplaceRegion region = WorkplaceRegion.parse("서울특별시 용산구 독서당로14길");

        assertEquals("서울특별시", region.sido());
        assertEquals("용산구", region.sigungu());
        assertEquals(Optional.of("서울특별시 용산구"), region.exactGroupKey());
        assertEquals(Optional.of("서울특별시"), region.broadenedGroupKey());
    }

    @Test
    @DisplayName("3토큰 이상이어도 앞 2토큰만 집단 키로 쓴다 — 전주시 덕진구는 '전주시' 집단")
    void usesOnlyFirstTwoTokens() {
        WorkplaceRegion region = WorkplaceRegion.parse("전북특별자치도 전주시 덕진구 백제대로");

        assertEquals("전북특별자치도", region.sido());
        assertEquals("전주시", region.sigungu());
        assertEquals(Optional.of("전북특별자치도 전주시"), region.exactGroupKey());
    }

    @Test
    @DisplayName("세종특별자치시처럼 시군구 계층이 없으면 EXACT 키는 없고 시도 키만 성립한다")
    void sejongHasNoSigungu() {
        WorkplaceRegion region = WorkplaceRegion.parse("세종특별자치시 한누리대로 2130");

        assertEquals("세종특별자치시", region.sido());
        assertFalse(region.hasSigungu());
        assertEquals(Optional.empty(), region.exactGroupKey());
        assertEquals(Optional.of("세종특별자치시"), region.broadenedGroupKey());
        assertTrue(region.isParseable());
    }

    @Test
    @DisplayName("두 번째 토큰이 시·군·구로 끝나지 않으면 시군구로 인정하지 않는다")
    void secondTokenMustBeSigungu() {
        WorkplaceRegion region = WorkplaceRegion.parse("경기도 도척윗로 12");

        assertEquals("경기도", region.sido());
        assertFalse(region.hasSigungu());
    }

    @Test
    @DisplayName("첫 토큰이 시도명이 아니거나 주소가 비면 파싱 불가 — 두 집단 키 모두 없다")
    void unparseableAddress() {
        for (String address : new String[]{null, "", "   ", "주소", "0", "성동구 연무장19길"}) {
            WorkplaceRegion region = WorkplaceRegion.parse(address);

            assertFalse(region.isParseable(), "파싱 가능으로 오판: " + address);
            assertEquals(Optional.empty(), region.exactGroupKey());
            assertEquals(Optional.empty(), region.broadenedGroupKey());
        }
    }

    @Test
    @DisplayName("행정구역 개편 전 명칭(강원도·전라북도)도 시도로 인정한다 — 과거 스냅샷 재적재 대비")
    void acceptsLegacySidoNames() {
        assertTrue(WorkplaceRegion.parse("강원도 원주시 서원대로").isParseable());
        assertTrue(WorkplaceRegion.parse("전라북도 전주시 백제대로").isParseable());
    }

    @Test
    @DisplayName("토큰 사이 공백이 여러 칸이어도 동일하게 파싱한다")
    void toleratesRepeatedWhitespace() {
        WorkplaceRegion region = WorkplaceRegion.parse("  부산광역시   해운대구  센텀중앙로 ");

        assertEquals("부산광역시", region.sido());
        assertEquals("해운대구", region.sigungu());
    }
}
