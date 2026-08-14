package github.lms.lemuel.payout.adapter.out.firmbanking.fep.codegen;

import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepField;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec.TelegramCatalog;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec.TelegramElement;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec.TelegramSpec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 전문 스펙 → 타입 안전 VO·코덱·설계서 생성기 (ADR 0033 Phase 2·3).
 *
 * <p><b>생성기는 test 소스셋에 있다.</b> 스펙 파서가 main 에 있으므로 생성기를 main 에 두면
 * "생성물을 컴파일하려면 생성기를 먼저 컴파일해야 하고, 생성기를 컴파일하려면 main 이 필요한" 순환이 생긴다.
 *
 * <p>생성물은 커밋되고, 스펙과의 일치는 {@code TelegramGeneratedSourcesTest} 가 빌드마다 대조한다.
 */
public final class TelegramCodeGenerator {

    /** 생성물이 놓이는 패키지. */
    public static final String PACKAGE = "github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.generated";

    private static final String HEADER = """
            // 이 파일은 telegram/firmbanking/*.yaml 에서 자동 생성된다 (ADR 0033).
            // 직접 고치지 말 것 — 스펙 YAML 을 고치고 재생성한다:
            //   ./gradlew :settlement-service:generateTelegramSources
            """;

    private TelegramCodeGenerator() {
    }

    /** 전체 카탈로그에 대한 자바 생성물 — 파일명(확장자 포함) → 소스. */
    public static Map<String, String> generate(TelegramCatalog catalog) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (TelegramSpec spec : catalog.specs()) {
            sources.put(voType(spec) + ".java", generateValueObject(spec));
            sources.put(codecType(spec) + ".java", generateCodec(spec));
        }
        return sources;
    }

    /** 전문 설계서(Markdown) — 파일명 → 내용. 사람이 읽는 규격서를 스펙에서 그대로 뽑는다. */
    public static Map<String, String> generateDocs(TelegramCatalog catalog) {
        Map<String, String> docs = new LinkedHashMap<>();
        for (TelegramSpec spec : catalog.specs()) {
            docs.put(docName(spec), generateDoc(spec));
        }
        docs.put("README.md", generateDocIndex(catalog));
        return docs;
    }

    // ─── VO ───────────────────────────────────────────────────────────────────

    private static String generateValueObject(TelegramSpec spec) {
        StringBuilder out = new StringBuilder(HEADER)
                .append("package ").append(PACKAGE).append(";\n\n");
        if (usesBigDecimal(spec)) out.append("import java.math.BigDecimal;\n");
        if (spec.repeatedGroup().isPresent()) out.append("import java.util.List;\n");
        out.append("\n/**\n * ").append(spec.description().isBlank() ? spec.name() : spec.description())
                .append(" — 전문구분코드 ").append(spec.msgType())
                .append(" · 개정 ").append(spec.version())
                .append(spec.isVariable()
                        ? " · 가변 길이(건수에 따라 달라진다)"
                        : " · 총 " + spec.totalLength() + "바이트")
                .append(".\n */\n")
                .append("public record ").append(voType(spec)).append("(\n");

        List<String> components = new ArrayList<>();
        for (TelegramElement element : spec.elements()) {
            switch (element) {
                case TelegramElement.Single single ->
                        components.add("        " + javaType(single.field()) + " " + camel(single.field().name()));
                case TelegramElement.RepeatedGroup group ->
                        components.add("        List<" + pascal(group.name()) + "> " + camel(group.name()) + "s");
            }
        }
        out.append(String.join(",\n", components)).append(") {\n");

        spec.repeatedGroup().ifPresent(group -> {
            out.append("\n    /** 반복부 ").append(group.name()).append(" 1건 — 최대 ")
                    .append(group.maxOccurrences()).append("건. */\n")
                    .append("    public record ").append(pascal(group.name())).append("(\n");
            List<String> nested = new ArrayList<>();
            for (FepField field : group.fields()) {
                nested.add("            " + javaType(field) + " " + camel(field.name()));
            }
            out.append(String.join(",\n", nested)).append(") {\n    }\n");
        });
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
                .append("import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec.TelegramSpec;\n\n")
                .append("import java.util.ArrayList;\n")
                .append("import java.util.LinkedHashMap;\n")
                .append("import java.util.List;\n")
                .append("import java.util.Map;\n\n")
                .append("/**\n * ").append(spec.name()).append(" 개정 ").append(spec.version())
                .append(" 코덱 — 스펙에서 생성된 타입 안전 인코딩·디코딩.\n */\n")
                .append("public final class ").append(codecType(spec)).append(" {\n\n")
                .append("    public static final String TELEGRAM = \"").append(spec.name()).append("\";\n")
                .append("    public static final String MSG_TYPE = \"").append(spec.msgType()).append("\";\n")
                .append("    public static final int VERSION = ").append(spec.version()).append(";\n");

        if (spec.isVariable()) {
            out.append("    /** 건수와 무관하게 확정된 선두 길이. */\n")
                    .append("    public static final int BASE_LENGTH = ").append(spec.baseLength()).append(";\n");
        } else {
            out.append("    public static final int TOTAL_LENGTH = ").append(spec.totalLength()).append(";\n");
        }

        spec.repeatedGroup().ifPresent(group -> out.append("    /** 반복부 ").append(group.name())
                .append(" 최대 건수. */\n    public static final int ").append(group.name()).append("_MAX = ")
                .append(group.maxOccurrences()).append(";\n"));

        out.append("\n    private static final TelegramSpec SPEC = FepLayouts.catalog().spec(TELEGRAM, VERSION);\n\n")
                .append("    private ").append(codecType(spec)).append("() {\n    }\n\n")
                .append(encodeMethod(spec, vo))
                .append("\n")
                .append(decodeMethod(spec, vo))
                .append("}\n");
        return out.toString();
    }

    private static String encodeMethod(TelegramSpec spec, String vo) {
        TelegramElement.RepeatedGroup group = spec.repeatedGroup().orElse(null);
        StringBuilder out = new StringBuilder()
                .append("    /** 값 → 고정길이 전문 바이트. */\n")
                .append("    public static byte[] encode(").append(vo).append(" telegram) {\n")
                .append("        Map<String, String> values = new LinkedHashMap<>();\n");

        String listVar = group == null ? null : camel(group.name()) + "s";
        if (group != null) {
            String accessor = "telegram." + listVar + "()";
            out.append("        List<").append(vo).append(".").append(pascal(group.name())).append("> ")
                    .append(listVar).append(" = ").append(accessor).append(" == null ? List.of() : ")
                    .append(accessor).append(";\n")
                    .append("        if (").append(listVar).append(".size() > ").append(group.name())
                    .append("_MAX) {\n            throw new FepProtocolException(\"반복부 ").append(group.name())
                    .append(" 최대 \" + ").append(group.name()).append("_MAX + \"건 초과: \" + ")
                    .append(listVar).append(".size());\n        }\n");
        }

        String countField = group instanceof TelegramElement.VariableRepeated variable ? variable.countField() : null;
        for (TelegramElement element : spec.elements()) {
            if (element instanceof TelegramElement.Single single) {
                FepField field = single.field();
                String value = field.name().equals(countField)
                        ? "TelegramCodecSupport.count(telegram." + camel(field.name()) + "(), "
                                + listVar + ".size(), \"" + field.name() + "\")"
                        : toWire(field, "telegram." + camel(field.name()) + "()");
                out.append("        values.put(\"").append(field.name()).append("\", ").append(value).append(");\n");
            }
        }
        if (group != null) {
            out.append("        for (int i = 0; i < ").append(listVar).append(".size(); i++) {\n")
                    .append("            var item = ").append(listVar).append(".get(i);\n")
                    .append("            String prefix = \"").append(group.name()).append("_\" + (i + 1) + \"_\";\n");
            for (FepField field : group.fields()) {
                out.append("            values.put(prefix + \"").append(field.name()).append("\", ")
                        .append(toWire(field, "item." + camel(field.name()) + "()")).append(");\n");
            }
            out.append("        }\n");
        }
        String layout = spec.isVariable()
                ? "SPEC.layoutFor(" + listVar + ".size())"
                : (group == null ? "SPEC.toLayout()" : "SPEC.toLayout()");
        return out.append("        return ").append(layout).append(".encode(values);\n    }\n").toString();
    }

    private static String decodeMethod(TelegramSpec spec, String vo) {
        TelegramElement.RepeatedGroup group = spec.repeatedGroup().orElse(null);
        StringBuilder out = new StringBuilder().append("    /**\n     * 고정길이 전문 바이트 → 값.\n");
        if (group instanceof TelegramElement.VariableRepeated variable) {
            out.append("     *\n     * <p>반복 건수는 건수 필드(").append(variable.countField())
                    .append(")를 먼저 읽어 정한다 — 길이가 건수에 따라 달라지므로\n")
                    .append("     * 레이아웃을 만들기 전에 건수를 알아야 한다.\n");
        } else if (group != null) {
            out.append("     *\n     * <p>반복부는 <b>선언된 최대 건수를 그대로</b> 돌려준다(빈 슬롯 포함).\n");
        }
        out.append("     */\n    public static ").append(vo).append(" decode(byte[] raw) {\n");

        if (spec.isVariable()) {
            out.append("        int occurrences = SPEC.readOccurrences(raw);\n")
                    .append("        Map<String, String> values = SPEC.layoutFor(occurrences).decode(raw);\n");
        } else {
            out.append("        Map<String, String> values = SPEC.toLayout().decode(raw);\n");
        }

        List<String> args = new ArrayList<>();
        for (TelegramElement element : spec.elements()) {
            switch (element) {
                case TelegramElement.Single single -> args.add("                "
                        + fromWire(single.field(), "values.get(\"" + single.field().name() + "\")"));
                case TelegramElement.RepeatedGroup repeated -> {
                    String var = camel(repeated.name()) + "s";
                    String bound = spec.isVariable() ? "occurrences" : repeated.name() + "_MAX";
                    out.append("        List<").append(vo).append(".").append(pascal(repeated.name())).append("> ")
                            .append(var).append(" = new ArrayList<>();\n")
                            .append("        for (int i = 1; i <= ").append(bound).append("; i++) {\n")
                            .append("            String prefix = \"").append(repeated.name()).append("_\" + i + \"_\";\n")
                            .append("            ").append(var).append(".add(new ").append(vo).append(".")
                            .append(pascal(repeated.name())).append("(\n");
                    List<String> nested = new ArrayList<>();
                    for (FepField field : repeated.fields()) {
                        nested.add("                    "
                                + fromWire(field, "values.get(prefix + \"" + field.name() + "\")"));
                    }
                    out.append(String.join(",\n", nested)).append("));\n        }\n");
                    args.add("                List.copyOf(" + var + ")");
                }
            }
        }
        return out.append("        return new ").append(vo).append("(\n")
                .append(String.join(",\n", args)).append(");\n    }\n").toString();
    }

    // ─── 설계서(Markdown) ──────────────────────────────────────────────────────

    private static String generateDoc(TelegramSpec spec) {
        StringBuilder out = new StringBuilder()
                .append("<!-- 이 파일은 telegram/firmbanking/*.yaml 에서 자동 생성된다 (ADR 0033). 직접 고치지 말 것. -->\n\n")
                .append("# ").append(spec.name()).append(" (").append(spec.msgType()).append(")")
                .append(spec.version() > 1 ? " — 개정 " + spec.version() : "").append("\n\n")
                .append("- 설명: ").append(spec.description().isBlank() ? "—" : spec.description()).append("\n")
                .append("- 전문구분코드: `").append(spec.msgType()).append("`\n")
                .append("- 개정: ").append(spec.version())
                .append(spec.effectiveFrom() == null ? "" : " (시행일 " + spec.effectiveFrom() + ")").append("\n");

        if (spec.isVariable()) {
            TelegramElement.VariableRepeated variable = (TelegramElement.VariableRepeated) spec.repeatedGroup()
                    .orElseThrow();
            out.append("- 길이: **가변** — 선두 ").append(spec.baseLength()).append("바이트 + ")
                    .append(variable.unitLength()).append("바이트 × 건수(`").append(variable.countField())
                    .append("`, 최대 ").append(variable.max()).append("건)\n")
                    .append("- 최대 길이: ").append(spec.lengthFor(variable.max())).append("바이트\n");
        } else {
            out.append("- 길이: ").append(spec.totalLength()).append("바이트 (고정)\n");
        }

        out.append("\n## 필드\n\n| offset | 필드 | 길이 | 타입 | 비고 |\n|---:|---|---:|---|---|\n");
        int offset = 0;
        for (TelegramElement element : spec.elements()) {
            if (element instanceof TelegramElement.RepeatedGroup group) {
                out.append("| ").append(offset).append(" | **").append(group.name()).append("** (반복) | ")
                        .append(group.unitLength()).append(" × n | — | ")
                        .append(group instanceof TelegramElement.VariableRepeated variable
                                ? "가변 — 건수 `" + variable.countField() + "`, 최대 " + variable.max() + "건"
                                : "고정 " + group.maxOccurrences() + "건")
                        .append(" |\n");
                int nested = offset;
                for (FepField field : group.fields()) {
                    out.append("| +").append(nested - offset).append(" | ").append(group.name()).append("_n_")
                            .append(field.name()).append(" | ").append(field.length()).append(" | ")
                            .append(field.type()).append(" | ").append(note(field)).append(" |\n");
                    nested += field.length();
                }
            } else {
                FepField field = ((TelegramElement.Single) element).field();
                out.append("| ").append(offset).append(" | ").append(field.name()).append(" | ")
                        .append(field.length()).append(" | ").append(field.type()).append(" | ")
                        .append(note(field)).append(" |\n");
                offset += field.length();
            }
        }
        return out.toString();
    }

    private static String generateDocIndex(TelegramCatalog catalog) {
        StringBuilder out = new StringBuilder()
                .append("<!-- 이 파일은 telegram/firmbanking/*.yaml 에서 자동 생성된다 (ADR 0033). 직접 고치지 말 것. -->\n\n")
                .append("# 펌뱅킹 전문 설계서\n\n")
                .append("스펙 단일 출처: `settlement-service/src/main/resources/telegram/firmbanking/*.yaml`\n")
                .append("재생성: `./gradlew :settlement-service:generateTelegramSources`\n\n")
                .append("| 코드 | 전문 | 개정 | 시행일 | 길이 |\n|---|---|---:|---|---|\n");
        // 목차는 전문구분코드·개정 순으로 고정한다 — 스펙 파일이 추가돼도 표 전체가 흔들리지 않는다.
        List<TelegramSpec> ordered = new ArrayList<>(catalog.specs());
        ordered.sort(Comparator.comparing(TelegramSpec::msgType).thenComparingInt(TelegramSpec::version));
        for (TelegramSpec spec : ordered) {
            out.append("| `").append(spec.msgType()).append("` | [").append(spec.name()).append("](")
                    .append(docName(spec)).append(") | ").append(spec.version()).append(" | ")
                    .append(spec.effectiveFrom() == null ? "—" : spec.effectiveFrom()).append(" | ")
                    .append(spec.isVariable() ? "가변" : spec.totalLength() + "바이트").append(" |\n");
        }
        return out.toString();
    }

    private static String note(FepField field) {
        return field.isDecimal() ? "금액 (scale " + field.scale() + ", BigDecimal)" : "—";
    }

    private static String docName(TelegramSpec spec) {
        return spec.msgType() + "-" + spec.name().toLowerCase(Locale.ROOT).replace('_', '-')
                + (spec.version() > 1 ? "-v" + spec.version() : "") + ".md";
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
        return spec.fieldsFor(1).stream().anyMatch(FepField::isDecimal);
    }

    static String voType(TelegramSpec spec) {
        return pascal(spec.name()) + versionSuffix(spec) + "Telegram";
    }

    static String codecType(TelegramSpec spec) {
        return pascal(spec.name()) + versionSuffix(spec) + "Codec";
    }

    private static String versionSuffix(TelegramSpec spec) {
        return spec.version() > 1 ? "V" + spec.version() : "";
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
