package quote

import "testing"

// Name() 은 로그·메트릭에서 "지금 어떤 구현이 틱을 만들고 있는지"를 답한다.
// ValueSource()("믿어도 되는 값인가")와 혼동되면 안 되므로 둘 다 고정한다.
func TestSimulatedSource_NameIsSimulated(t *testing.T) {
	s := NewSimulatedSource(42)
	if got := s.Name(); got != "simulated" {
		t.Fatalf("Name(): got %q, want %q", got, "simulated")
	}
	if s.Name() == s.ValueSource() {
		t.Fatal("Name 과 ValueSource 는 서로 다른 질문에 답한다 — 같은 값이면 하나가 잘못됐다")
	}
}

// SimulatedSource 는 QuoteSource 를 만족해야 Hub 에 꽂힌다.
var _ QuoteSource = (*SimulatedSource)(nil)
