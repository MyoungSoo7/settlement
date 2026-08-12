package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec;

import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepField;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepProtocolException;

import java.util.ArrayList;
import java.util.List;

/**
 * 전문 본문의 구성 요소 — 단일 필드이거나 반복부(OCCURS)다.
 *
 * <p>런타임 코덱은 어차피 평평한 필드 목록만 다루지만({@link #flatten()}), 스펙은 <b>반복 구조를
 * 구조인 채로</b> 보존한다. 코드 생성이 {@code List<Detail>} 을 만들려면 "이 6개 필드가 5번 반복된다"는
 * 사실이 남아 있어야 하고, 평평해진 뒤에는 이름 규칙으로 되짚는 수밖에 없기 때문이다.
 */
public sealed interface TelegramElement {

    /** 이 요소가 전문에서 차지하는 바이트 길이. */
    int byteLength();

    /** 코덱이 쓰는 평평한 필드 목록으로 펼친다. */
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
     * 반복부 — 같은 필드 묶음이 {@code count} 번 반복된다.
     *
     * <p>고정 반복만 지원한다(가변부 없음). 펼친 이름은 {@code <그룹>_<1부터의 인덱스>_<필드>} —
     * 예: {@code DETAIL_3_REF_ID}.
     */
    record Repeated(String name, int count, List<FepField> fields) implements TelegramElement {

        public Repeated {
            if (name == null || name.isBlank()) throw new FepProtocolException("반복부 name 필수");
            if (count <= 0) throw new FepProtocolException("반복 횟수는 1 이상: " + name + " → " + count);
            if (fields == null || fields.isEmpty()) throw new FepProtocolException("반복부 fields 필수: " + name);
            fields = List.copyOf(fields);
        }

        @Override
        public int byteLength() {
            return count * fields.stream().mapToInt(FepField::length).sum();
        }

        @Override
        public List<FepField> flatten() {
            List<FepField> flat = new ArrayList<>(count * fields.size());
            for (int index = 1; index <= count; index++) {
                for (FepField field : fields) {
                    flat.add(new FepField(
                            name + "_" + index + "_" + field.name(), field.length(), field.type(), field.scale()));
                }
            }
            return flat;
        }
    }
}
