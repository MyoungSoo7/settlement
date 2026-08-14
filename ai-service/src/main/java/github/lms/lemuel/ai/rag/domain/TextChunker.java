package github.lms.lemuel.ai.rag.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 문서를 검색 단위(청크)로 자르는 순수 도메인 규칙.
 *
 * <p><b>왜 문단 우선인가:</b> 고정 길이로 자르면 문장·표가 중간에서 끊겨, 검색으로 찾아낸 조각이
 * 그 자체로는 뜻이 통하지 않는다(= 근거로 못 쓴다). 빈 줄로 구분된 문단을 최소 단위로 보고
 * {@code maxChars} 까지 <b>이어 붙이는</b> 방식이라 의미 경계를 최대한 보존한다.
 *
 * <p><b>왜 오버랩이 필요한가:</b> 답이 청크 경계에 걸쳐 있으면 두 청크 모두 답을 반쪽만 담아
 * 어느 쪽도 상위 랭크에 오르지 못한다. 문단 하나가 {@code maxChars} 를 넘어 강제로 잘릴 때만
 * {@code overlapChars} 만큼 겹쳐, 경계에 걸린 문장이 최소 한 청크 안에 온전히 들어가게 한다.
 *
 * <p>불변식(테스트로 못박음):
 * <ol>
 *   <li>모든 청크의 길이 ≤ {@code maxChars}</li>
 *   <li>모든 청크는 비어 있지 않다(공백만인 청크 없음)</li>
 *   <li>원문의 모든 문단은 최소 한 청크에 온전히 포함된다 (강제 분할된 초장문 문단 제외)</li>
 *   <li>빈 문서 → 빈 리스트 (0청크 문서는 "지식 없음"이라 검색 동작이 종전과 같다)</li>
 * </ol>
 */
public final class TextChunker {

    /** 문단 구분 — 빈 줄 1개 이상(공백 포함 허용). CRLF 도 처리. */
    // 문단 구분 — 줄바꿈 2개 이상(사이에 가로 공백 허용). CRLF 는 한 줄바꿈으로 센다.
    //
    // ★ 그룹 반복 (?:...){2,} 을 쓰지 않는다. Java 정규식은 그룹 반복을 재귀로 처리해서, 연속 빈 줄이
    //   많은 문서가 들어오면 StackOverflowError 가 난다 — 이론이 아니라 실측이다(TextChunkerLargeInputTest:
    //   빈 줄 50,000 개에서 Pattern.java:4615 StackOverflowError). 소유 수량자를 안쪽에 붙이는 것으로는
    //   해결되지 않았다. 지적 대상이 안쪽 백트래킹이 아니라 바깥 그룹 반복이기 때문이다(S5998).
    //
    // 그래서 "줄바꿈 → 가로공백 → 줄바꿈 → 나머지 공백 일괄" 구조로 편다. 남은 반복은 전부 문자 클래스라
    // Java 가 재귀 없이 순회한다. 세 번째 이후의 빈 줄은 마지막 [ \t\r\n]*+ 가 한 번에 삼킨다.
    // CRLF 안전성: \r?\n 을 두 번 요구하므로 "\r\n" 하나는 문단 경계가 되지 않는다.
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\r?\\n[ \\t]*+\\r?\\n[ \\t\\r\\n]*+");

    private static final String JOINER = "\n\n";

    private TextChunker() {
    }

    /**
     * @param text          원문 (마스킹 이후의 본문을 넣는다 — 마스킹은 호출자 책임)
     * @param maxChars      청크 최대 길이
     * @param overlapChars  강제 분할 시 겹칠 길이 (0 이면 겹치지 않음)
     */
    public static List<String> chunk(String text, int maxChars, int overlapChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars 는 1 이상이어야 합니다 (요청: " + maxChars + ")");
        }
        if (overlapChars < 0) {
            throw new IllegalArgumentException("overlapChars 는 0 이상이어야 합니다 (요청: " + overlapChars + ")");
        }
        if (overlapChars >= maxChars) {
            // step = maxChars - overlapChars 가 0 이하가 되면 무한 루프다 — 설정 실수를 즉시 거부한다.
            throw new IllegalArgumentException(
                    "overlapChars 는 maxChars 보다 작아야 합니다 (maxChars=" + maxChars + ", overlapChars=" + overlapChars + ")");
        }
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // 1) 문단 단위로 쪼개고, maxChars 를 넘는 문단은 오버랩을 두고 강제 분할한다.
        List<String> units = new ArrayList<>();
        for (String paragraph : PARAGRAPH_BREAK.split(text.strip())) {
            String unit = paragraph.strip();
            if (unit.isEmpty()) {
                continue;
            }
            if (unit.length() <= maxChars) {
                units.add(unit);
            } else {
                units.addAll(hardSplit(unit, maxChars, overlapChars));
            }
        }

        // 2) maxChars 를 넘지 않는 한 문단을 이어 붙여 청크를 만든다(문맥 보존 + 청크 수 절감).
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            if (current.isEmpty()) {
                current.append(unit);
            } else if (current.length() + JOINER.length() + unit.length() <= maxChars) {
                current.append(JOINER).append(unit);
            } else {
                chunks.add(current.toString());
                current.setLength(0);
                current.append(unit);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return List.copyOf(chunks);
    }

    /** maxChars 를 넘는 단일 문단을 overlapChars 만큼 겹쳐 자른다. */
    private static List<String> hardSplit(String unit, int maxChars, int overlapChars) {
        List<String> parts = new ArrayList<>();
        int step = maxChars - overlapChars;   // 위에서 > 0 을 보장
        for (int start = 0; start < unit.length(); start += step) {
            int end = Math.min(unit.length(), start + maxChars);
            parts.add(unit.substring(start, end));
            if (end == unit.length()) {
                break;
            }
        }
        return parts;
    }
}
