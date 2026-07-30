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
 * <p>첫 토큰은 <b>광역 단위 접미사</b>로 판정한다(특별시·광역시·특별자치시·특별자치도, 그리고 3자 이상의
 * …도). 이 규칙은 {@code "주소"}·{@code "0"} 같은 원본 이형이나 시군구만 적힌 주소({@code "성동구 …"})를
 * 거부하면서, 행정구역 개편으로 생기는 새 명칭도 그대로 받아들인다.
 *
 * <p>★ 처음에는 광역단체 명칭 <b>목록 대조</b>로 구현했는데, 55만 행 실적재 검증에서 목록에 없는
 * {@code 전남광역통합특별시} 계열 명칭 때문에 3만 4천 건(6.2%)이 통째로 파싱 불가로 떨어졌다.
 * 명칭 목록은 행정통합·개편이 있을 때마다 조용히 낡아 한 지역의 비교가 전부 사라지는 실패 모드를 갖는다 —
 * 접미사 규칙은 그 실패 모드가 없고, 실데이터 18종 시도 값을 모두 커버한다(개편 전 강원도·전라북도 포함).
 * 바깥 접미사만 쓰는 이유: 맨 "시"를 허용하면 {@code 경기도 광주시} 의 {@code 광주시} 같은 시군구가 시도로
 * 오인될 여지가 생긴다.
 */
public record WorkplaceRegion(String sido, String sigungu) {

    /** 광역자치단체 접미사. */
    private static final Set<String> SIDO_SUFFIXES = Set.of("특별시", "광역시", "특별자치시", "특별자치도");

    /** …도(경기도·충청남도·강원도)는 접미사가 1자라 최소 길이로 오탐을 막는다. */
    private static final int MIN_DO_LENGTH = 3;

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
        if (!isSido(tokens[0])) {
            return UNPARSEABLE;
        }
        String sigungu = tokens.length >= 2 && isSigungu(tokens[1]) ? tokens[1] : null;
        return new WorkplaceRegion(tokens[0], sigungu);
    }

    private static boolean isSido(String token) {
        return SIDO_SUFFIXES.stream().anyMatch(token::endsWith)
                || (token.endsWith("도") && token.length() >= MIN_DO_LENGTH);
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
