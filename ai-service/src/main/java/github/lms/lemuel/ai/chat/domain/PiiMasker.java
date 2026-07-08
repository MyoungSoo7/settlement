package github.lms.lemuel.ai.chat.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 채팅 입력의 민감정보(카드번호·주민등록번호)를 <b>저장·LLM 전송 전</b>에 마스킹한다.
 *
 * <p>돈을 다루는 플랫폼의 AI 채팅창은 사용자가 실수로 PII 를 붙여넣기 쉬운 유출 경로다.
 * 마스킹하지 않으면 (1) {@code chat_messages.content} 평문 저장, (2) 첫 메시지면 대화 제목,
 * (3) 외부 LLM(Anthropic) 전송으로 새어나간다. 이 클래스가 세 경로의 단일 초크포인트다
 * (ChatService 진입부에서 한 번 적용).
 *
 * <p>정책은 <b>차단이 아니라 마스킹</b> — 사용자가 실수로 넣어도 대화는 이어지되 원문은 남기지 않는다.
 * 순수 도메인 규칙(프레임워크 의존 없음, 정규식만).
 */
public final class PiiMasker {

    /**
     * 주민등록번호: 6자리 생년월일 + (성별/세기 자리 1~8) + 6자리. 구분자(-, 공백) 1개 허용.
     * 성별 자리 제약으로 임의의 13자리 숫자 오탐을 줄인다.
     */
    private static final Pattern RRN = Pattern.compile("(?<!\\d)\\d{6}[- ]?[1-8]\\d{6}(?!\\d)");

    /** 카드번호 후보: 13~19 자리 숫자(공백/하이픈 구분 허용). Luhn 통과분만 실제 마스킹한다. */
    private static final Pattern CARD_CANDIDATE = Pattern.compile("(?<!\\d)(?:\\d[ -]?){13,19}(?<=\\d)");

    private static final String RRN_MASK = "[주민번호 마스킹됨]";
    private static final String CARD_MASK = "[카드번호 마스킹됨]";

    private PiiMasker() {
    }

    /** 입력에서 감지된 카드번호·주민등록번호를 마스킹 토큰으로 치환한다. null/blank 는 그대로 반환. */
    public static String mask(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        // 주민번호를 먼저 치환(토큰엔 숫자가 없어 카드 스캔과 겹치지 않는다).
        String masked = RRN.matcher(input).replaceAll(RRN_MASK);
        return maskCards(masked);
    }

    private static String maskCards(String input) {
        Matcher matcher = CARD_CANDIDATE.matcher(input);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String candidate = matcher.group();
            String digits = candidate.replaceAll("[^0-9]", "");
            String replacement = luhnValid(digits) ? CARD_MASK : candidate;
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Luhn 체크섬 — 실제 카드번호는 통과, 무작위 숫자열 오탐을 걸러낸다. */
    private static boolean luhnValid(String digits) {
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleDigit) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }
}
