package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec;

import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepField;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepProtocolException;

import java.util.ArrayList;
import java.util.List;

/**
 * 전문 본문의 구성 요소 — 단일 필드, 고정 반복부, 가변 반복부 셋 중 하나다.
 *
 * <p>런타임 코덱은 어차피 평평한 필드 목록만 다루지만, 스펙은 <b>반복 구조를 구조인 채로</b> 보존한다.
 * 코드 생성이 {@code List<Detail>} 을 만들려면 "이 필드 묶음이 반복된다"는 사실이 남아 있어야 한다.
 */
public sealed interface TelegramElement {

    /** 건수와 무관하게 확정된 바이트 길이. 가변 반복부는 0 — 건수를 알아야 길이가 정해진다. */
    int byteLength();

    /** 건수와 무관하게 확정된 필드 목록. 가변 반복부는 빈 목록. */
    List<FepField> flatten();

    /** 단일 필드. */
    record Single(FepField field) implements TelegramElement {

        public Single {
            if (field == null) throw new FepProtocolException("필드 필수");
        }

        @Override
        public int byteLength() {
            return field.length();
        }

        @Override
        public List<FepField> flatten() {
            return List.of(field);
        }
    }

    /**
     * 고정 반복부 — 같은 필드 묶음이 정확히 {@code count} 번 반복된다. 전문 길이가 항상 일정하다.
     *
     * <p>펼친 이름은 {@code <그룹>_<1부터의 인덱스>_<필드>} — 예: {@code DETAIL_3_REF_ID}.
     */
    record Repeated(String name, int count, List<FepField> fields) implements TelegramElement, RepeatedGroup {

        public Repeated {
            if (name == null || name.isBlank()) throw new FepProtocolException("반복부 name 필수");
            if (count <= 0) throw new FepProtocolException("반복 횟수는 1 이상: " + name + " → " + count);
            if (fields == null || fields.isEmpty()) throw new FepProtocolException("반복부 fields 필수: " + name);
            fields = List.copyOf(fields);
        }

        @Override
        public int byteLength() {
            return count * unitLength();
        }

        @Override
        public List<FepField> flatten() {
            return expand(count);
        }

        @Override
        public int maxOccurrences() {
            return count;
        }
    }

    /**
     * 가변 반복부 — 반복 횟수를 전문 안의 건수 필드({@code countField})가 알려준다. 전문 길이가 건수에 따라 달라진다.
     *
     * <p>실무 다건이체는 건수가 매번 다르므로 고정 반복은 빈 슬롯을 채워 보내는 낭비가 된다. 대신
     * <b>디코딩 전에 건수를 먼저 읽어야</b> 하므로, 건수 필드는 반드시 이 그룹보다 앞에 선언되어야 한다.
     */
    record VariableRepeated(String name, String countField, int max, List<FepField> fields)
            implements TelegramElement, RepeatedGroup {

        public VariableRepeated {
            if (name == null || name.isBlank()) throw new FepProtocolException("반복부 name 필수");
            if (countField == null || countField.isBlank()) {
                throw new FepProtocolException("가변 반복부 countField 필수: " + name);
            }
            if (max <= 0) throw new FepProtocolException("반복 최대 건수는 1 이상: " + name + " → " + max);
            if (fields == null || fields.isEmpty()) throw new FepProtocolException("반복부 fields 필수: " + name);
            fields = List.copyOf(fields);
        }

        /** 건수를 모르는 동안 이 그룹은 길이에 기여하지 않는다. */
        @Override
        public int byteLength() {
            return 0;
        }

        /** 건수를 모르는 동안 펼칠 수 없다. */
        @Override
        public List<FepField> flatten() {
            return List.of();
        }

        @Override
        public int maxOccurrences() {
            return max;
        }
    }

    /** 반복부 공통 — 고정·가변이 공유하는 것(1건 길이, 펼치기, 최대 건수). */
    sealed interface RepeatedGroup extends TelegramElement permits Repeated, VariableRepeated {

        String name();

        List<FepField> fields();

        int maxOccurrences();

        /** 반복 1건의 바이트 길이. */
        default int unitLength() {
            return fields().stream().mapToInt(FepField::length).sum();
        }

        /** 주어진 건수만큼 펼친 필드 목록. */
        default List<FepField> expand(int occurrences) {
            if (occurrences < 0) throw new FepProtocolException("반복 건수는 0 이상: " + name() + " → " + occurrences);
            if (occurrences > maxOccurrences()) {
                throw new FepProtocolException(
                        "반복 최대 건수 초과: " + name() + " " + occurrences + " > " + maxOccurrences());
            }
            List<FepField> flat = new ArrayList<>(occurrences * fields().size());
            for (int index = 1; index <= occurrences; index++) {
                for (FepField field : fields()) {
                    flat.add(new FepField(
                            name() + "_" + index + "_" + field.name(), field.length(), field.type(), field.scale()));
                }
            }
            return flat;
        }
    }
}
