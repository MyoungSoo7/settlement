package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec;

import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepField;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepProtocolException;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.TelegramLayout;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 전문 스펙 1건 — 스펙 파일(YAML) 1개를 파싱·검증한 결과. 코드가 아니라 <b>설계서의 표현</b>이다.
 *
 * <p>공통부는 {@code include} 로 조립되어 {@link #fields()} 에 이미 병합된 상태로 들어온다
 * (선언 순서 = byte offset 이므로 공통부가 항상 선두).
 *
 * @param name          전문 식별자 (카탈로그 내 유일)
 * @param msgType       전문구분코드 (카탈로그 내 유일)
 * @param description   사람이 읽는 설명 — 설계서 생성 입력
 * @param version       전문 개정 번호 (미기재 시 1)
 * @param effectiveFrom 시행일 — 보관만 하고 라우팅에는 아직 쓰지 않는다(ADR 0033 Phase 3)
 * @param fields        공통부 병합 후 필드 목록
 */
public record TelegramSpec(
        String name,
        String msgType,
        String description,
        int version,
        LocalDate effectiveFrom,
        List<FepField> fields) {

    public TelegramSpec {
        if (name == null || name.isBlank()) throw new FepProtocolException("전문 식별자(telegram) 필수");
        if (msgType == null || msgType.isBlank()) throw new FepProtocolException("전문구분코드(msgType) 필수: " + name);
        if (version <= 0) throw new FepProtocolException("전문 개정번호는 1 이상: " + name);
        if (fields == null || fields.isEmpty()) throw new FepProtocolException("필드 목록 필수: " + name);
        fields = List.copyOf(fields);

        Set<String> seen = new HashSet<>();
        for (FepField field : fields) {
            if (!seen.add(field.name())) {
                throw new FepProtocolException("필드명 중복: " + name + "." + field.name());
            }
        }
    }

    /** 전문 총 바이트 길이 (공통부 포함). */
    public int totalLength() {
        return fields.stream().mapToInt(FepField::length).sum();
    }

    /** 런타임 코덱으로 변환 — 인코딩·패딩 로직은 기존 {@link TelegramLayout} 이 그대로 담당한다. */
    public TelegramLayout toLayout() {
        return new TelegramLayout(fields);
    }
}
