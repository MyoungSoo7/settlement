package quote

import (
	"encoding/json"
	"testing"
)

// 스트림이 내보내는 값이 "믿어도 되는 값"인지 소비자가 알 수 있어야 한다.
//
// market-service 의 REST 응답은 source 를 SAMPLE/EXCHANGE 로 명시해 근사 샘플과 거래소 실시세를
// 구분한다. 그런데 이 스트림은 랜덤워크로 만든 틱을 gateway(/api/market-stream/**) 로 외부에
// 내보내면서 아무 표기도 싣지 않았다 — 받는 쪽에서는 실시세와 구분할 방법이 없다.
func TestTick_CarriesValueSource(t *testing.T) {
	tk := NewTick("005930", 71234.567, mustTime(), SourceSample)

	if tk.Source != SourceSample {
		t.Errorf("source: got %q, want %q", tk.Source, SourceSample)
	}

	// JSON 키까지 고정한다 — 소비자(프론트 SSE 핸들러)가 보는 계약이다.
	raw, err := json.Marshal(tk)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if got, ok := decoded["source"]; !ok || got != "SAMPLE" {
		t.Errorf("json payload source: got %v (present=%v), want \"SAMPLE\"", got, ok)
	}
}

// 두 소스 모두 합성 틱을 만든다. PollingSource 는 base 만 market-service 실값으로 갱신할 뿐
// 틱 자체는 랜덤워크라, EXCHANGE 를 달면 "실시세"라고 거짓말하는 셈이 된다.
func TestSources_DeclareSampleUntilRealFeedExists(t *testing.T) {
	sim := NewSimulatedSource(42)
	if got := sim.ValueSource(); got != SourceSample {
		t.Errorf("simulated: got %q, want %q", got, SourceSample)
	}

	poll := NewPollingSource(sim, "http://market-service:8094", 0, nil)
	if got := poll.ValueSource(); got != SourceSample {
		t.Errorf("polling(base 만 실데이터, 틱은 합성): got %q, want %q", got, SourceSample)
	}
}
