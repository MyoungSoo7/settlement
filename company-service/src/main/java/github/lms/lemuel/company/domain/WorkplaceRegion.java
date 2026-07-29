package github.lms.lemuel.company.domain;

import java.util.Optional;
import java.util.Set;

/**
 * 주소에서 파생한 지역 집단. 원본 주소의 앞 2토큰(시도 + 시군구)까지만 쓴다 — 읍면동까지 내려가면
 * 표본이 한 자릿수로 붕괴한다.
 *
 * <p>파싱 규칙을 SQL 과 Java 에 이중 구현하면 집계와 조회가 서로 다른 집단을 가리키게 되므로,
 * 파생은 적재 시점에 이 클래스로 한 번만 하고 결과를 컬럼에 저장한다(집계 SQL 은 그 컬럼만 GROUP BY).
 *
 * <p>첫 토큰이 시도 명칭 목록에 없으면 파싱 불가로 본다. 접미사(…시/…도) 규칙보다 목록 대조가
 * 안전하다 — 정부 원본에는 {@code "주소"}·{@code "0"} 같은 이형 값이 실제로 존재하고, 접미사 규칙은
 * 그런 값이나 시군구만 적힌 주소를 시도로 오인해 엉뚱한 집단을 만든다.
 */
public record WorkplaceRegion(String sido, String sigungu) {

    /** 광역자치단체 17개 + 행정구역 개편 전 명칭(과거 스냅샷 재적재 대비). */
    private static final Set<String> SIDO_NAMES = Set.of(
            "서울특별시", "부산광역시", "대구광역시", "인천광역시", "광주광역시", "대전광역시", "울산광역시",
            "세종특별자치시", "경기도", "강원특별자치도", "충청북도", "충청남도", "전북특별자치도", "전라남도",
            "경상북도", "경상남도", "제주특별자치도",
            "강원도", "전라북도", "제주도");

    private static final WorkplaceRegion UNPARSEABLE = new WorkplaceRegion(null, null);

    /**
     * 주소 앞 2토큰을 시도·시군구로 해석한다. 시도를 못 뽑으면 두 집단 키 모두 없는 값을 돌려준다
     * (지역 비교만 {@link ComparisonUnavailableReason#REGION_UNPARSEABLE} 로 떨어진다).
     *
     * <p>세종특별자치시처럼 시군구 계층이 없는 주소는 시도만 성립한다 — EXACT 단계를 건너뛰고
     * 시도 집단(BROADENED)으로 바로 간다.
     */
    public static WorkplaceRegion parse(String address) {
        if (address == null || address.isBlank()) {
            return UNPARSEABLE;
        }
        String[] tokens = address.strip().split("\\s+");
        if (!SIDO_NAMES.contains(tokens[0])) {
            return UNPARSEABLE;
        }
        String sigungu = tokens.length >= 2 && isSigungu(tokens[1]) ? tokens[1] : null;
        return new WorkplaceRegion(tokens[0], sigungu);
    }

    private static boolean isSigungu(String token) {
        return token.length() >= 2
                && (token.endsWith("시") || token.endsWith("군") || token.endsWith("구"));
    }

    public boolean isParseable() {
        return sido != null;
    }

    public boolean hasSigungu() {
        return sigungu != null;
    }

    /** 시도+시군구 집단 키(세부 단계). */
    public Optional<String> exactGroupKey() {
        return isParseable() && hasSigungu() ? Optional.of(sido + ' ' + sigungu) : Optional.empty();
    }

    /** 시도 집단 키(상위 단계). */
    public Optional<String> broadenedGroupKey() {
        return Optional.ofNullable(sido);
    }
}
