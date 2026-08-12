package github.lms.lemuel.payout.adapter.out.firmbanking.fep.codegen;

import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepField;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec.TelegramCatalog;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec.TelegramElement;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec.TelegramSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 전문 스펙 → 타입 안전 VO·코덱 소스 생성기 (ADR 0033 Phase 2).
 *
 * <p><b>생성기는 test 소스셋에 있다.</b> 스펙 파서가 main 에 있으므로 생성기를 main 에 두면
 * "생성물을 컴파일하려면 생성기를 먼저 컴파일해야 하고, 생성기를 컴파일하려면 main 이 필요한"
 * 순환이 생긴다. test 소스셋은 main 을 자유롭게 쓰므로 순환이 없다.
 *
 * <p>생성물은 {@code src/main/java/.../fep/protocol/generated/} 에 <b>커밋</b>되고,
 * 스펙과의 일치는 {@code TelegramGeneratedSourcesTest} 가 빌드마다 대조한다.
 */
public final class TelegramCodeGenerator {

    /** 생성물이 놓이는 패키지. */
    public static final String PACKAGE = "github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.generated";

    private static final String HEADER = """
            // 이 파일은 telegram/firmbanking/*.yaml 에서 자동 생성된다 (ADR 0033 Phase 2).
            // 직접 고치지 말 것 — 스펙 YAML 을 고치고 재생성한다:
            //   ./gradlew :settlement-service:generateTelegramSources
            """;

    private TelegramCodeGenerator() {
    }

    /** 전체 카탈로그에 대한 생성물 — 파일명(확장자 포함) → 소스. */
    public static Map<String, String> generate(TelegramCatalog catalog) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String name : catalog.names()) {
            TelegramSpec spec = catalog.spec(name);
            sources.put(voType(spec) + ".java", generateValueObject(spec));
            sources.put(codecType(spec) + ".java", generateCodec(spec));
        }
        return sources;
    }

    // ─── VO ───────────────────────────────────────────────────────────────────

    private static String generateValueObject(TelegramSpec spec) {
        StringBuilder out = new StringBuilder(HEADER)
                .append("package ").append(PACKAGE).append(";\n\n");
        if (usesBigDecimal(spec)) out.append("import java.math.BigDecimal;\n");
        if (hasGroup(spec)) out.append("import java.util.List;\n");
        out.append("\n/**\n * ").append(spec.description().isBlank() ? spec.name() : spec.description())
                .append(" — 전문구분코드 ").append(spec.msgType())
                .append(" · 총 ").append(spec.totalLength()).append("바이트.\n */\n")
                .append("public record ").append(voType(spec)).append("(\n");

        List<String> components = new ArrayList<>();
        for (TelegramElement element : spec.elements()) {
            switch (element) {
                case TelegramElement.Single single ->
                        components.add("        " + javaType(single.field()) + " " + camel(single.field().name()));
                case TelegramElement.Repeated group ->
                        components.add("        List<" + pascal(group.name()) + "> " + camel(group.name()) + "s");
            }
        }
        out.append(String.join(",\n", components)).append(") {\n");

        for (TelegramElement element : spec.elements()) {
            if (element instanceof TelegramElement.Repeated group) {
                out.append("\n    /** 반복부 ").append(group.name()).append(" 1건 — 최대 ")
                        .append(group.count()).append("건. */\n")
                        .append("    public record ").append(pascal(group.name())).append("(\n");
                List<String> nested = new ArrayList<>();
                for (FepField field : group.fields()) {
                    nested.add("            " + javaType(field) + " " + camel(field.name()));
                }
                out.append(String.join(",\n", nested)).append(") {\n    }\n");
            }
        }
        return out.append("}\n").toString();
    }

    // ─── Codec ────────────────────────────────────────────────────────────────

    private static String generateCodec(TelegramSpec spec) {
        String vo = voType(spec);
        StringBuilder out = new StringBuilder(HEADER)
                .append("package ").append(PACKAGE).append(";\n\n")
                .append("import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepLayouts;\n")
                .append("import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepProtocolException;\n")
                .append("import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.TelegramCodecSupport;\n")
                .append("import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.TelegramLayout;\n\n")
                .append("import java.util.ArrayList;\n")
                .append("import java.util.LinkedHashMap;\n")
                .append("import java.util.List;\n")
                .append("import java.util.Map;\n\n")
                .append("/**\n * ").append(spec.name())
                .append(" 코덱 — 스펙에서 생성된 타입 안전 인코딩·디코딩.\n */\n")
                .append("public final class ").append(codecType(spec)).append(" {\n\n")
                .append("    /** 전문 식별자 — 레이아웃은 스펙 카탈로그가 단일 출처다. */\n")
                .append("    public static final String TELEGRAM = \"").append(spec.name()).append("\";\n")
                .append("    public static final String MSG_TYPE = \"").append(spec.msgType()).append("\";\n")
                .append("    public static final int TOTAL_LENGTH = ").append(spec.totalLength()).append(";\n\n");

        for (TelegramElement element : spec.elements()) {
            if (element instanceof TelegramElement.Repeated group) {
                out.append("    /** 반복부 ").append(group.name()).append(" 최대 건수. */\n")
                        .append("    public static final int ").append(group.name()).append("_MAX = ")
                        .append(group.count()).append(";\n\n");
            }
        }

        out.append("    private static final TelegramLayout LAYOUT = FepLayouts.catalog().layout(TELEGRAM);\n\n")
                .append("    private ").append(codecType(spec)).append("() {\n    }\n\n")
                .append(encodeMethod(spec, vo))
                .append("\n")
                .append(decodeMethod(spec, vo))
                .append("}\n");
        return out.toString();
    }

    private static String encodeMethod(TelegramSpec spec, String vo) {
        StringBuilder out = new StringBuilder()
                .append("    /** 값 → 고정길이 전문 바이트. */\n")
                .append("    public static byte[] encode(").append(vo).append(" telegram) {\n")
                .append("        Map<String, String> values = new LinkedHashMap<>();\n");
        for (TelegramElement element : spec.elements()) {
            switch (element) {
                case TelegramElement.Single single -> out.append("        values.put(\"")
                        .append(single.field().name()).append("\", ")
                        .append(toWire(single.field(), "telegram." + camel(single.field().name()) + "()"))
                        .append(");\n");
                case TelegramElement.Repeated group -> {
                    String list = "telegram." + camel(group.name()) + "s()";
                    out.append("        List<").append(vo).append(".").append(pascal(group.name())).append("> ")
                            .append(camel(group.name())).append("s = ").append(list)
                            .append(" == null ? List.of() : ").append(list).append(";\n")
                            .append("        if (").append(camel(group.name())).append("s.size() > ")
                            .append(group.name()).append("_MAX) {\n")
                            .append("            throw new FepProtocolException(\"반복부 ").append(group.name())
                            .append(" 최대 \" + ").append(group.name()).append("_MAX + \"건 초과: \" + ")
                            .append(camel(group.name())).append("s.size());\n        }\n")
                            .append("        for (int i = 0; i < ").append(camel(group.name())).append("s.size(); i++) {\n")
                            .append("            var item = ").append(camel(group.name())).append("s.get(i);\n")
                            .append("            String prefix = \"").append(group.name()).append("_\" + (i + 1) + \"_\";\n");
                    for (FepField field : group.fields()) {
                        out.append("            values.put(prefix + \"").append(field.name()).append("\", ")
                                .append(toWire(field, "item." + camel(field.name()) + "()")).append(");\n");
                    }
                    out.append("        }\n");
                }
            }
        }
        return out.append("        return LAYOUT.encode(values);\n    }\n").toString();
    }

    private static String decodeMethod(TelegramSpec spec, String vo) {
        StringBuilder out = new StringBuilder()
                .append("    /**\n")
                .append("     * 고정길이 전문 바이트 → 값.\n")
                .append("     *\n")
                .append("     * <p>반복부는 <b>선언된 최대 건수를 그대로</b> 돌려준다(빈 슬롯 포함). 유효 건수는 전문의\n")
                .append("     * 건수 필드가 알려주며, 값이 비었다는 이유로 슬롯을 버리면 은행이 보낸 실패 건을 놓친다.\n")
                .append("     */\n")
                .append("    public static ").append(vo).append(" decode(byte[] raw) {\n")
                .append("        Map<String, String> values = LAYOUT.decode(raw);\n");

        List<String> args = new ArrayList<>();
        for (TelegramElement element : spec.elements()) {
            switch (element) {
                case TelegramElement.Single single ->
                        args.add("                " + fromWire(single.field(), "values.get(\"" + single.field().name() + "\")"));
                case TelegramElement.Repeated group -> {
                    String var = camel(group.name()) + "s";
                    out.append("        List<").append(vo).append(".").append(pascal(group.name())).append("> ")
                            .append(var).append(" = new ArrayList<>();\n")
                            .append("        for (int i = 1; i <= ").append(group.name()).append("_MAX; i++) {\n")
                            .append("            String prefix = \"").append(group.name()).append("_\" + i + \"_\";\n")
                            .append("            ").append(var).append(".add(new ").append(vo).append(".")
                            .append(pascal(group.name())).append("(\n");
                    List<String> nested = new ArrayList<>();
                    for (FepField field : group.fields()) {
                        nested.add("                    " + fromWire(field, "values.get(prefix + \"" + field.name() + "\")"));
                    }
                    out.append(String.join(",\n", nested)).append("));\n        }\n");
                    args.add("                List.copyOf(" + var + ")");
                }
            }
        }
        return out.append("        return new ").append(vo).append("(\n")
                .append(String.join(",\n", args)).append(");\n    }\n").toString();
    }

    // ─── 타입·이름 규칙 ────────────────────────────────────────────────────────

    private static String toWire(FepField field, String accessor) {
        return field.isDecimal()
                ? "TelegramCodecSupport.digits(" + accessor + ", " + field.scale() + ", \"" + field.name() + "\")"
                : "TelegramCodecSupport.text(" + accessor + ")";
    }

    private static String fromWire(FepField field, String accessor) {
        return field.isDecimal()
                ? "TelegramCodecSupport.decimal(" + accessor + ", " + field.scale() + ", \"" + field.name() + "\")"
                : accessor;
    }

    private static String javaType(FepField field) {
        return field.isDecimal() ? "BigDecimal" : "String";
    }

    private static boolean usesBigDecimal(TelegramSpec spec) {
        return spec.fields().stream().anyMatch(FepField::isDecimal);
    }

    private static boolean hasGroup(TelegramSpec spec) {
        return spec.elements().stream().anyMatch(TelegramElement.Repeated.class::isInstance);
    }

    static String voType(TelegramSpec spec) {
        return pascal(spec.name()) + "Telegram";
    }

    static String codecType(TelegramSpec spec) {
        return pascal(spec.name()) + "Codec";
    }

    /** {@code TRANSFER_REQUEST} → {@code TransferRequest} */
    static String pascal(String snake) {
        StringBuilder out = new StringBuilder();
        for (String part : snake.split("_")) {
            if (part.isEmpty()) continue;
            out.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    /** {@code MSG_TYPE} → {@code msgType} */
    static String camel(String snake) {
        String pascal = pascal(snake);
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }
}
