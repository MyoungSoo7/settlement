package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec;

import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepProtocolException;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.TelegramLayout;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 전문 카탈로그 — 스펙 파일 묶음을 이름·전문구분코드로 조회하는 불변 색인.
 *
 * <p><b>개정 병존</b>(ADR 0033 Phase 3): 같은 전문구분코드에 여러 개정이 공존할 수 있다. 은행이 규격을
 * 바꿔도 시행일 전에는 구 규격이, 이후에는 신 규격이 오가기 때문이다. 그래서 유일성은 코드가 아니라
 * {@code (전문명, 개정번호)} 로 잡고, 수신 해석은 {@link #byMsgType(String, LocalDate)} 가 시행일로 고른다.
 */
public final class TelegramCatalog {

    /** (전문명, 개정번호) → 스펙. */
    private final Map<String, TelegramSpec> byNameAndVersion;
    /** 전문구분코드 → 개정 목록(시행일 오름차순). */
    private final Map<String, List<TelegramSpec>> byMsgType;
    private final Set<String> names;

    private TelegramCatalog(Map<String, TelegramSpec> byNameAndVersion,
                            Map<String, List<TelegramSpec>> byMsgType,
                            Set<String> names) {
        // ⚠ Map.copyOf/Set.copyOf 를 쓰지 않는다 — 불변 컬렉션은 JVM 실행마다 달라지는 해시 순서를 쓴다.
        // 이 카탈로그의 순회 순서가 곧 코드·설계서 생성 순서라, 순서가 흔들리면 같은 스펙에서 매번
        // 다른 산출물이 나와 드리프트 게이트가 무작위로 깨진다. 선언(파일명) 순서를 그대로 보존한다.
        this.byNameAndVersion = Collections.unmodifiableMap(new LinkedHashMap<>(byNameAndVersion));
        this.byMsgType = Collections.unmodifiableMap(new LinkedHashMap<>(byMsgType));
        this.names = Collections.unmodifiableSet(new LinkedHashSet<>(names));
    }

    public static TelegramCatalog of(Collection<TelegramSpec> specs) {
        Map<String, TelegramSpec> indexed = new LinkedHashMap<>();
        Map<String, List<TelegramSpec>> revisions = new LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>();

        for (TelegramSpec spec : specs) {
            TelegramSpec duplicate = indexed.put(key(spec.name(), spec.version()), spec);
            if (duplicate != null) {
                throw new FepProtocolException("전문 개정 중복: " + spec.name() + " v" + spec.version());
            }
            names.add(spec.name());
            revisions.computeIfAbsent(spec.msgType(), code -> new java.util.ArrayList<>()).add(spec);
        }

        revisions.forEach((code, list) -> {
            Set<String> distinctNames = new LinkedHashSet<>(list.stream().map(TelegramSpec::name).toList());
            if (distinctNames.size() > 1) {
                throw new FepProtocolException(
                        "전문구분코드 중복: " + code + " (" + distinctNames + ") — 같은 코드는 한 전문의 개정이어야 한다");
            }
            if (list.size() > 1) {
                for (TelegramSpec spec : list) {
                    if (spec.effectiveFrom() == null) {
                        throw new FepProtocolException(
                                "개정이 둘 이상이면 effectiveFrom 필수: " + spec.name() + " v" + spec.version());
                    }
                }
                Set<LocalDate> dates = new LinkedHashSet<>(list.stream().map(TelegramSpec::effectiveFrom).toList());
                if (dates.size() != list.size()) {
                    throw new FepProtocolException(
                            "같은 시행일의 개정이 둘 이상이다: " + list.getFirst().name() + " → " + dates);
                }
            }
            list.sort(Comparator.comparing(TelegramSpec::effectiveFrom,
                    Comparator.nullsFirst(Comparator.naturalOrder())));
        });

        Map<String, List<TelegramSpec>> immutable = new LinkedHashMap<>();
        revisions.forEach((code, list) -> immutable.put(code, List.copyOf(list)));
        return new TelegramCatalog(indexed, immutable, names);
    }

    /** 이름으로 조회 — 개정이 여럿이면 <b>가장 최신 개정</b>. */
    public TelegramSpec spec(String name) {
        return byNameAndVersion.values().stream()
                .filter(spec -> spec.name().equals(name))
                .max(Comparator.comparingInt(TelegramSpec::version))
                .orElseThrow(() -> new FepProtocolException("알 수 없는 전문: " + name + " (등록: " + names + ")"));
    }

    /** 이름·개정번호로 정확히 조회. */
    public TelegramSpec spec(String name, int version) {
        TelegramSpec spec = byNameAndVersion.get(key(name, version));
        if (spec == null) throw new FepProtocolException("알 수 없는 전문 개정: " + name + " v" + version);
        return spec;
    }

    /** 최신 개정의 코덱. 가변 전문이면 실패한다 — {@code spec(name).layoutFor(n)} 을 쓴다. */
    public TelegramLayout layout(String name) {
        return spec(name).toLayout();
    }

    /**
     * 전문구분코드로 조회 — 개정이 하나뿐일 때만 쓴다.
     * 여럿이면 어느 규격으로 해석할지 알 수 없으므로 시행일을 요구한다.
     */
    public TelegramSpec byMsgType(String msgType) {
        List<TelegramSpec> revisions = revisionsOf(msgType);
        if (revisions.size() > 1) {
            throw new FepProtocolException("개정이 여럿인 전문구분코드다 — 시행일 기준 조회를 쓴다: "
                    + msgType + " (개정 " + revisions.size() + "종)");
        }
        return revisions.getFirst();
    }

    /** 전문구분코드 + 기준일로 조회 — 기준일에 <b>시행 중인</b> 가장 최근 개정. */
    public TelegramSpec byMsgType(String msgType, LocalDate asOf) {
        if (asOf == null) throw new FepProtocolException("기준일 필수: " + msgType);
        return revisionsOf(msgType).stream()
                .filter(spec -> spec.effectiveFrom() == null || !spec.effectiveFrom().isAfter(asOf))
                .reduce((earlier, later) -> later)
                .orElseThrow(() -> new FepProtocolException(
                        "해당 기준일에 시행 중인 개정이 없다: " + msgType + " @ " + asOf));
    }

    /** 전문구분코드의 모든 개정(시행일 오름차순). */
    public List<TelegramSpec> revisionsOf(String msgType) {
        List<TelegramSpec> revisions = byMsgType.get(msgType);
        if (revisions == null) throw new FepProtocolException("알 수 없는 전문구분코드: " + msgType);
        return revisions;
    }

    /** 전문 이름 목록(개정 제외). */
    public Set<String> names() {
        return names;
    }

    /** 모든 스펙(개정 포함). */
    public List<TelegramSpec> specs() {
        return List.copyOf(byNameAndVersion.values());
    }

    /** 스펙 개수(개정 포함). */
    public int size() {
        return byNameAndVersion.size();
    }

    private static String key(String name, int version) {
        return name + "#v" + version;
    }
}
