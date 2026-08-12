package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec;

import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepField;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepFieldType;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepProtocolException;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.TelegramLayout;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 전문 스펙 1건 — 스펙 파일(YAML) 1개를 파싱·검증한 결과. 코드가 아니라 <b>설계서의 표현</b>이다.
 *
 * <p>공통부는 {@code include} 로 조립되어 {@link #elements()} 에 이미 병합된 상태로 들어온다
 * (선언 순서 = byte offset 이므로 공통부가 항상 선두).
 *
 * <p><b>고정 전문</b>은 길이가 항상 같아 {@link #totalLength()}·{@link #toLayout()} 이 그대로 성립한다.
 * <b>가변 전문</b>(가변 반복부를 가진 전문)은 건수를 알아야 길이가 정해지므로 {@link #lengthFor(int)}·
 * {@link #layoutFor(int)} 를 쓰고, 수신 전문은 {@link #readOccurrences(byte[])} 로 건수를 먼저 읽는다.
 *
 * @param name          전문 식별자 ({@code (name, version)} 이 카탈로그 내 유일)
 * @param msgType       전문구분코드 — 같은 코드의 여러 개정이 공존할 수 있다
 * @param description   사람이 읽는 설명 — 설계서 생성 입력
 * @param version       전문 개정 번호 (미기재 시 1)
 * @param effectiveFrom 시행일 — 같은 전문구분코드의 개정 라우팅 기준({@link TelegramCatalog#byMsgType})
 * @param elements      공통부 병합 후 구성 요소 — 단일 필드와 반복부가 선언 순서대로 섞여 있다
 */
public record TelegramSpec(
        String name,
        String msgType,
        String description,
        int version,
        LocalDate effectiveFrom,
        List<TelegramElement> elements) {

    public TelegramSpec {
        if (name == null || name.isBlank()) throw new FepProtocolException("전문 식별자(telegram) 필수");
        if (msgType == null || msgType.isBlank()) throw new FepProtocolException("전문구분코드(msgType) 필수: " + name);
        if (version <= 0) throw new FepProtocolException("전문 개정번호는 1 이상: " + name);
        if (elements == null || elements.isEmpty()) throw new FepProtocolException("필드 목록 필수: " + name);
        elements = List.copyOf(elements);

        long groups = elements.stream().filter(TelegramElement.RepeatedGroup.class::isInstance).count();
        if (groups > 1) {
            throw new FepProtocolException("반복부는 전문당 1개까지 지원한다: " + name + " (" + groups + "개)");
        }
        validateVariableGroup(name, elements);

        // 중복 검사는 펼친 뒤에 한다 — 반복부 이름이 기존 필드와 충돌하면 펼친 시점에야 드러난다.
        Set<String> seen = new HashSet<>();
        for (TelegramElement element : elements) {
            List<FepField> expanded = element instanceof TelegramElement.RepeatedGroup group
                    ? group.expand(group.maxOccurrences())
                    : element.flatten();
            for (FepField field : expanded) {
                if (!seen.add(field.name())) {
                    throw new FepProtocolException("필드명 중복: " + name + "." + field.name());
                }
            }
        }
    }

    /** 가변 반복부는 맨 끝에 있어야 하고, 건수 필드는 그보다 앞의 N 필드여야 한다. */
    private static void validateVariableGroup(String name, List<TelegramElement> elements) {
        int index = 0;
        for (TelegramElement element : elements) {
            if (element instanceof TelegramElement.VariableRepeated variable) {
                if (index != elements.size() - 1) {
                    throw new FepProtocolException(
                            "가변 반복부는 전문 마지막에 와야 한다(뒤 필드의 offset 이 건수에 따라 밀린다): "
                                    + name + "." + variable.name());
                }
                FepField counter = elements.subList(0, index).stream()
                        .flatMap(previous -> previous.flatten().stream())
                        .filter(field -> field.name().equals(variable.countField()))
                        .findFirst()
                        .orElseThrow(() -> new FepProtocolException("건수 필드를 반복부 앞에서 찾을 수 없다: "
                                + name + " → countField=" + variable.countField()));
                if (counter.type() != FepFieldType.N) {
                    throw new FepProtocolException("건수 필드는 N 이어야 한다: " + name + "." + counter.name());
                }
            }
            index++;
        }
    }

    /** 이 전문의 반복부(있다면). */
    public Optional<TelegramElement.RepeatedGroup> repeatedGroup() {
        return elements.stream()
                .filter(TelegramElement.RepeatedGroup.class::isInstance)
                .map(TelegramElement.RepeatedGroup.class::cast)
                .findFirst();
    }

    /** 건수에 따라 길이가 달라지는 전문인가. */
    public boolean isVariable() {
        return elements.stream().anyMatch(TelegramElement.VariableRepeated.class::isInstance);
    }

    /** 건수와 무관하게 확정된 선두 길이 — 가변 전문에서 건수 필드를 읽어낼 수 있는 범위. */
    public int baseLength() {
        return elements.stream().mapToInt(TelegramElement::byteLength).sum();
    }

    /** 코덱이 쓰는 평평한 필드 목록. 가변 전문은 {@link #fieldsFor(int)} 를 쓴다. */
    public List<FepField> fields() {
        requireFixed();
        return fieldsFor(0);
    }

    /** 반복 건수를 지정해 펼친 필드 목록. */
    public List<FepField> fieldsFor(int occurrences) {
        List<FepField> flat = new ArrayList<>();
        for (TelegramElement element : elements) {
            if (element instanceof TelegramElement.VariableRepeated variable) {
                flat.addAll(variable.expand(occurrences));
            } else {
                flat.addAll(element.flatten());
            }
        }
        return List.copyOf(flat);
    }

    /** 고정 전문의 총 바이트 길이. */
    public int totalLength() {
        requireFixed();
        return baseLength();
    }

    /** 반복 건수를 지정한 총 바이트 길이. */
    public int lengthFor(int occurrences) {
        TelegramElement.RepeatedGroup group = repeatedGroup().orElse(null);
        if (group instanceof TelegramElement.VariableRepeated variable) {
            if (occurrences > variable.max()) {
                throw new FepProtocolException(
                        "반복 최대 건수 초과: " + name + " " + occurrences + " > " + variable.max());
            }
            return baseLength() + occurrences * variable.unitLength();
        }
        return totalLength();
    }

    /** 고정 전문의 런타임 코덱. */
    public TelegramLayout toLayout() {
        requireFixed();
        return new TelegramLayout(fields());
    }

    /** 반복 건수를 지정한 런타임 코덱 — 가변 전문은 이 경로로만 인코딩·디코딩한다. */
    public TelegramLayout layoutFor(int occurrences) {
        return new TelegramLayout(fieldsFor(occurrences));
    }

    /**
     * 수신 전문에서 반복 건수를 읽는다 — 가변 전문을 디코딩하려면 <b>레이아웃을 만들기 전에</b> 필요하다.
     *
     * <p>건수 필드는 반복부보다 앞에 있으므로 선두 {@link #baseLength()} 바이트만으로 읽어낼 수 있다.
     */
    public int readOccurrences(byte[] raw) {
        TelegramElement.RepeatedGroup group = repeatedGroup().orElse(null);
        if (!(group instanceof TelegramElement.VariableRepeated variable)) {
            throw new FepProtocolException("가변 전문이 아니다: " + name);
        }
        if (raw == null || raw.length < baseLength()) {
            throw new FepProtocolException("전문이 선두 규격보다 짧다: " + name + " 수신 "
                    + (raw == null ? "null" : raw.length) + " < " + baseLength() + " 바이트");
        }
        List<FepField> prefix = new ArrayList<>();
        elements.forEach(element -> prefix.addAll(element.flatten()));
        String value = new TelegramLayout(prefix)
                .decode(Arrays.copyOfRange(raw, 0, baseLength()))
                .get(variable.countField());
        try {
            int occurrences = Integer.parseInt(value.trim());
            if (occurrences < 0 || occurrences > variable.max()) {
                throw new FepProtocolException("건수 필드가 규격을 벗어났다: " + name + "."
                        + variable.countField() + "=" + occurrences + " (최대 " + variable.max() + ")");
            }
            return occurrences;
        } catch (NumberFormatException e) {
            throw new FepProtocolException(
                    "건수 필드가 숫자가 아니다: " + name + "." + variable.countField() + "='" + value + "'", e);
        }
    }

    private void requireFixed() {
        if (isVariable()) {
            throw new FepProtocolException(
                    "가변 전문은 건수 없이 길이·레이아웃을 정할 수 없다: " + name + " (layoutFor/lengthFor 를 쓴다)");
        }
    }
}
