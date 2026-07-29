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
    @DisplayName("실데이터에 등장하는 시도 18종을 전부 인정한다 — 개편 전 명칭(강원도·전라북도)과 "
            + "행정통합으로 새로 생긴 명칭(전남광주통합특별시)까지 (명칭 목록 방식이 3만4천건을 "
            + "파싱 불가로 떨어뜨린 회귀를 고정)")
    void acceptsEverySidoSeenInRealData() {
        for (String sido : new String[]{"경기도", "서울특별시", "전남광주통합특별시", "경상남도", "부산광역시",
                "인천광역시", "경상북도", "충청남도", "충청북도", "대구광역시", "전북특별자치도",
                "강원특별자치도", "대전광역시", "울산광역시", "제주특별자치도", "세종특별자치시",
                "전라북도", "강원도"}) {
            WorkplaceRegion region = WorkplaceRegion.parse(sido + " 순천시 율촌산단4로");

            assertTrue(region.isParseable(), "시도로 인정하지 못함: " + sido);
            assertEquals(sido, region.sido());
            assertEquals("순천시", region.sigungu());
        }
    }

    @Test
    @DisplayName("시군구·2자 이하 토큰은 시도로 오인하지 않는다 — '광주시'가 첫 토큰이어도 시도가 아니다")
    void doesNotMistakeSigunguForSido() {
        for (String notSido : new String[]{"광주시", "성동구", "순천군", "대도", "주소"}) {
            assertFalse(WorkplaceRegion.parse(notSido + " 어딘가로 1").isParseable(),
                    "시도로 오인: " + notSido);
        }
    }

    @Test
    @DisplayName("토큰 사이 공백이 여러 칸이어도 동일하게 파싱한다")
    void toleratesRepeatedWhitespace() {
        WorkplaceRegion region = WorkplaceRegion.parse("  부산광역시   해운대구  센텀중앙로 ");

        assertEquals("부산광역시", region.sido());
        assertEquals("해운대구", region.sigungu());
    }
}
