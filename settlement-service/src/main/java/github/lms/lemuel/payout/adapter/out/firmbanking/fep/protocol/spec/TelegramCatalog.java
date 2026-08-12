package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec;

import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepProtocolException;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.TelegramLayout;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 전문 카탈로그 — 스펙 파일 묶음을 이름/전문구분코드로 조회하는 불변 색인.
 *
 * <p>이름·전문구분코드 <b>양쪽 모두 유일</b>해야 한다. 같은 코드를 두 전문이 선언하면
 * 수신 전문을 어느 레이아웃으로 해석할지 결정할 수 없으므로 로딩 자체를 실패시킨다.
 */
public final class TelegramCatalog {

    private final Map<String, TelegramSpec> byName;
    private final Map<String, TelegramSpec> byMsgType;

    private TelegramCatalog(Map<String, TelegramSpec> byName, Map<String, TelegramSpec> byMsgType) {
        this.byName = Map.copyOf(byName);
        this.byMsgType = Map.copyOf(byMsgType);
    }

    public static TelegramCatalog of(Collection<TelegramSpec> specs) {
        Map<String, TelegramSpec> names = new LinkedHashMap<>();
        Map<String, TelegramSpec> types = new LinkedHashMap<>();
        for (TelegramSpec spec : specs) {
            TelegramSpec dupName = names.put(spec.name(), spec);
            if (dupName != null) throw new FepProtocolException("전문 식별자 중복: " + spec.name());
            TelegramSpec dupType = types.put(spec.msgType(), spec);
            if (dupType != null) {
                throw new FepProtocolException(
                        "전문구분코드 중복: " + spec.msgType() + " (" + dupType.name() + " vs " + spec.name() + ")");
            }
        }
        return new TelegramCatalog(names, types);
    }

    /** 이름으로 코덱 조회. 없으면 즉시 실패 — 오타를 런타임 빈 전문으로 흘려보내지 않는다. */
    public TelegramLayout layout(String name) {
        return spec(name).toLayout();
    }

    public TelegramSpec spec(String name) {
        TelegramSpec spec = byName.get(name);
        if (spec == null) throw new FepProtocolException("알 수 없는 전문: " + name + " (등록: " + byName.keySet() + ")");
        return spec;
    }

    /** 전문구분코드로 조회 — 수신 전문의 공통부 4바이트로 레이아웃을 찾을 때 쓴다. */
    public TelegramSpec byMsgType(String msgType) {
        TelegramSpec spec = byMsgType.get(msgType);
        if (spec == null) throw new FepProtocolException("알 수 없는 전문구분코드: " + msgType);
        return spec;
    }

    public Set<String> names() {
        return byName.keySet();
    }

    public int size() {
        return byName.size();
    }
}
