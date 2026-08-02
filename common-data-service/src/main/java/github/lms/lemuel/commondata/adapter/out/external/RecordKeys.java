package github.lms.lemuel.commondata.adapter.out.external;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * recordKey 해석 규칙 — 제공처(data.go.kr/서울)와 무관한 공통 멱등 키 계약.
 *
 * <p>keyFields 값을 {@code |} 로 조인하되, 결측/과대(>{@value #MAX_KEY_LENGTH})/부재면
 * payload SHA-256 으로 폴백한다(폴백은 멱등성 보장 수단, 버그 아님).
 */
final class RecordKeys {

    static final String KEY_JOIN = "|";
    static final int MAX_KEY_LENGTH = 300;

    private RecordKeys() {
    }

    static String resolve(JsonNode item, List<String> keyFields, String payload) {
        if (!keyFields.isEmpty()) {
            List<String> values = new ArrayList<>(keyFields.size());
            for (String field : keyFields) {
                String value = item.path(field).asText("");
                if (value.isBlank()) {
                    values = null;
                    break;
                }
                values.add(value);
            }
            if (values != null) {
                String key = String.join(KEY_JOIN, values);
                if (key.length() <= MAX_KEY_LENGTH) {
                    return key;
                }
            }
        }
        return sha256(payload);
    }

    private static String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 JVM", e);
        }
    }
}
