package github.lms.lemuel.deposit.adapter.out.llm;

import com.fasterxml.jackson.databind.JsonNode;
import github.lms.lemuel.common.ocr.VisionExtractionClient;
import github.lms.lemuel.common.ocr.VisionExtractionException;
import github.lms.lemuel.deposit.application.port.out.ExtractTransferProofPort;
import github.lms.lemuel.deposit.config.ProofOcrProperties;
import github.lms.lemuel.deposit.domain.ExtractedTransferProof;
import github.lms.lemuel.deposit.domain.exception.DepositProofOcrUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Gemini 비전 기반 이체확인증 OCR — shared-common {@link VisionExtractionClient} 위에 예치금 증빙
 * 도메인 지식만 얹는다(ADR 0036 확산). settlement·card·insurance·loan 어댑터와 같은 구조.
 *
 * <p><b>무폴백</b>: 호출 실패·형식 파손·이체금액 판독 실패는 전부
 * {@link DepositProofOcrUnavailableException}(503). 금액은 원장 기표의 근거라 지어낼 수 없다.
 * 입금자명·이체일은 판독 실패를 null 로 표현하고(이체일 null 은 첨부 시점에 NEEDS_REVIEW 로 흐른다),
 * 신뢰도 누락은 보수적 0.50 — 리뷰 큐행이다.
 *
 * <p><b>PII 최소화</b>: 계좌번호는 프롬프트에서 요구하지 않는다 — deposit 에 대조할 정본이 없는
 * 민감값은 아예 다루지 않는다.
 */
@Component
public class GeminiTransferProofOcrAdapter implements ExtractTransferProofPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiTransferProofOcrAdapter.class);

    /** 모델이 신뢰도를 주지 않았을 때의 보수적 기본값 — 리뷰 큐로 흐르게 한다. */
    private static final BigDecimal FALLBACK_CONFIDENCE = new BigDecimal("0.50");

    private static final String PROMPT = """
            첨부한 한국 은행 이체확인증(입금확인증) 이미지에서 다음 필드를 읽어 JSON 으로만 답하라.
            추측하지 말고, 읽을 수 없는 필드는 null 로 둔다.
            계좌번호·주민등록번호 등 개인식별번호는 절대 포함하지 마라.
            {
              "senderName": "입금자(보내는 분) 성명",
              "transferDate": "이체일(YYYY-MM-DD)",
              "transferAmount": "이체금액(숫자만)",
              "confidence": "판독 신뢰도 0~1"
            }
            """;

    private final VisionExtractionClient client;

    /** 생성자가 둘(운영용·테스트 주입용)이라 스프링이 쓸 쪽을 명시한다. */
    @Autowired
    public GeminiTransferProofOcrAdapter(ProofOcrProperties properties) {
        this(new VisionExtractionClient(properties.baseUrl(), properties.apiKey(),
                properties.model(), properties.maxOutputTokens()));
    }

    /** 테스트 전용 — 클라이언트를 밖에서 주입한다. */
    GeminiTransferProofOcrAdapter(VisionExtractionClient client) {
        this.client = client;
        if (!client.isConfigured()) {
            log.warn("app.deposit.proof-ocr.api-key 미설정 — 예치금 증빙 첨부는 503 으로 응답합니다.");
        }
    }

    @Override
    public boolean isConfigured() {
        return client.isConfigured();
    }

    @Override
    public String modelName() {
        return client.modelName();
    }

    @Override
    public ExtractedTransferProof extract(byte[] content, String contentType) {
        JsonNode fields;
        try {
            fields = client.extractJson(content, contentType, PROMPT);
        } catch (VisionExtractionException e) {
            log.warn("[deposit] 증빙 OCR 추출 실패: {}", e.getMessage());
            throw new DepositProofOcrUnavailableException("증빙 판독에 실패했습니다: " + e.getMessage());
        }
        return mapFields(fields);
    }

    /** 모델이 돌려준 JSON 객체를 증빙 추출 결과로 옮긴다 — 필드 해석의 정본. */
    static ExtractedTransferProof mapFields(JsonNode fields) {
        return new ExtractedTransferProof(
                text(fields, "senderName"),
                softDate(text(fields, "transferDate")),
                requiredAmount(text(fields, "transferAmount")),
                confidence(text(fields, "confidence")));
    }

    private static String text(JsonNode fields, String name) {
        JsonNode node = fields.path(name);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    /** 이체일 판독 실패는 null — 첨부 시점에 NEEDS_REVIEW 로 흐른다. */
    private static LocalDate softDate(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 콤마·원 표기 등 장식을 걷어내고 숫자만 남긴다. 이체금액이 없으면 지어내지 않고 끊는다. */
    private static BigDecimal requiredAmount(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("[^0-9.]", "");
        if (digits.isEmpty() || digits.equals(".")) {
            throw new DepositProofOcrUnavailableException("이체금액을 읽지 못했습니다: " + raw);
        }
        try {
            return new BigDecimal(digits);
        } catch (NumberFormatException e) {
            throw new DepositProofOcrUnavailableException("이체금액을 읽지 못했습니다: " + raw);
        }
    }

    private static BigDecimal confidence(String raw) {
        if (raw == null) {
            return FALLBACK_CONFIDENCE;
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(raw.replaceAll("[^0-9.]", ""));
        } catch (RuntimeException e) {
            return FALLBACK_CONFIDENCE;
        }
        if (parsed.signum() < 0 || parsed.compareTo(BigDecimal.ONE) > 0) {
            return FALLBACK_CONFIDENCE;
        }
        return parsed;
    }
}
