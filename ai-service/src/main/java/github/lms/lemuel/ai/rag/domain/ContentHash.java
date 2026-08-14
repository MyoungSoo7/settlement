package github.lms.lemuel.ai.rag.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 문서 본문의 내용 지문(SHA-256 hex 64자) — 재임베딩 여부를 판정하는 유일한 근거.
 *
 * <p>임베딩은 청크 수만큼 외부 유료 API 를 호출한다. 같은 문서를 다시 적재하는 흔한 실수의 비용을
 * 0 으로 만들기 위해, 마스킹 후 본문의 해시가 이전과 같으면 임베딩 자체를 건너뛴다.
 *
 * <p>해시 대상은 <b>마스킹 이후</b> 본문이다 — 저장·전송되는 실체와 지문이 일치해야
 * "지문이 같으면 저장된 것도 같다"가 성립한다.
 *
 * <p>보안 용도가 아니라 동일성 판정용이므로 salt·HMAC 이 필요 없다.
 */
public final class ContentHash {

    private ContentHash() {
    }

    /** 마스킹된 본문의 SHA-256 을 소문자 hex 64자로 반환한다. */
    public static String of(String content) {
        if (content == null) {
            throw new IllegalArgumentException("해시 대상 본문이 null 입니다");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 JDK 표준 필수 알고리즘 — 도달 불가.
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }
}
