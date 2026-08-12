package github.lms.lemuel.payout.adapter.out.firmbanking.fep.codegen;

import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepField;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepFieldType;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.TelegramLayout;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec.TelegramCatalog;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec.TelegramElement;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec.TelegramSpec;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec.TelegramSpecLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 생성된 코덱 <b>전 종</b> 왕복 검증 — 손으로 케이스를 적는 대신 스펙에서 표본을 만든다.
 *
 * <p>계약은 <b>decode → encode 가 원본 바이트를 그대로 복원</b>하는 것. 필드 하나라도 코덱에서
 * 빠지거나 순서가 틀리면 바이트가 어긋나 실패한다. 전문·개정이 늘어도 이 테스트는 그대로 따라간다.
 */
class TelegramGeneratedCodecRoundTripTest {

    /** 가변 전문 표본의 반복 건수. */
    private static final int SAMPLE_OCCURRENCES = 2;

    private static final TelegramCatalog CATALOG =
            TelegramSpecLoader.loadFromClasspath(TelegramSpecLoader.FIRMBANKING_LOCATION);

    static List<String> specKeys() {
        return CATALOG.specs().stream().map(spec -> spec.name() + "#v" + spec.version()).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("specKeys")
    @DisplayName("생성 코덱은 전문 바이트를 손실 없이 왕복한다")
    void roundTripsThroughGeneratedCodec(String key) throws Exception {
        String name = key.substring(0, key.indexOf("#v"));
        int version = Integer.parseInt(key.substring(key.indexOf("#v") + 2));
        TelegramSpec spec = CATALOG.spec(name, version);

        int occurrences = spec.isVariable() ? SAMPLE_OCCURRENCES : 0;
        TelegramLayout layout = spec.isVariable() ? spec.layoutFor(occurrences) : spec.toLayout();
        byte[] original = layout.encode(sampleValues(spec, occurrences));

        Class<?> codec = Class.forName(TelegramCodeGenerator.PACKAGE + "." + TelegramCodeGenerator.codecType(spec));
        Method decode = codec.getMethod("decode", byte[].class);
        Object value = decode.invoke(null, (Object) original);

        Method encode = codec.getMethod("encode", value.getClass());
        byte[] reEncoded = (byte[]) encode.invoke(null, value);

        assertThat(reEncoded).as("%s 왕복 바이트", key).isEqualTo(original);
        assertThat(reEncoded).hasSize(spec.isVariable() ? spec.lengthFor(occurrences) : spec.totalLength());
        assertThat(codec.getField("MSG_TYPE").get(null)).isEqualTo(spec.msgType());
        assertThat(codec.getField("VERSION").get(null)).isEqualTo(spec.version());
    }

    /**
     * 스펙에서 만든 표본값 — 레이아웃이 패딩한 뒤의 모습이 곧 정본이므로, 어떤 값을 넣든
     * decode→encode 는 같은 바이트로 돌아와야 한다. 가변 전문은 건수 필드를 실제 건수에 맞춘다.
     */
    private static Map<String, String> sampleValues(TelegramSpec spec, int occurrences) {
        String countField = spec.repeatedGroup()
                .filter(TelegramElement.VariableRepeated.class::isInstance)
                .map(group -> ((TelegramElement.VariableRepeated) group).countField())
                .orElse(null);

        Map<String, String> values = new LinkedHashMap<>();
        for (FepField field : spec.fieldsFor(occurrences)) {
            if (field.name().equals(countField)) {
                values.put(field.name(), String.valueOf(occurrences));
            } else if (field.type() == FepFieldType.N) {
                values.put(field.name(), "1");
            } else {
                values.put(field.name(), "AB".substring(0, Math.min(2, field.length())));
            }
        }
        return values;
    }
}
