package quote

import (
	"math"
	"testing"
)

func TestSimulatedSource_Deterministic(t *testing.T) {
	const code = "005930"
	const base = 70000.0

	a := NewSimulatedSource(42)
	b := NewSimulatedSource(42)

	for i := 0; i < 100; i++ {
		pa := a.Next(code, base)
		pb := b.Next(code, base)
		if pa != pb {
			t.Fatalf("step %d: same seed produced different prices: %v vs %v", i, pa, pb)
		}
	}
}

func TestSimulatedSource_DifferentSeedsDiverge(t *testing.T) {
	const code = "005930"
	const base = 70000.0

	a := NewSimulatedSource(1)
	b := NewSimulatedSource(2)

	diverged := false
	for i := 0; i < 50; i++ {
		if a.Next(code, base) != b.Next(code, base) {
			diverged = true
			break
		}
	}
	if !diverged {
		t.Fatal("different seeds produced identical walk for 50 steps")
	}
}

func TestSimulatedSource_BoundedWalk(t *testing.T) {
	const code = "000660"
	const base = 100000.0

	s := NewSimulatedSource(7)
	low := base * (1 - s.bandPct)
	high := base * (1 + s.bandPct)

	for i := 0; i < 10000; i++ {
		p := s.Next(code, base)
		if p < low-0.001 || p > high+0.001 {
			t.Fatalf("step %d: price %v escaped band [%v, %v]", i, p, low, high)
		}
	}
}

func TestSimulatedSource_SetBaseResetsWalk(t *testing.T) {
	const code = "035720"
	s := NewSimulatedSource(3)
	_ = s.Next(code, 50000)
	s.SetBase(code, 90000)

	// After SetBase the walk should center on the new base.
	sum := 0.0
	const n = 500
	for i := 0; i < n; i++ {
		sum += s.Next(code, 90000)
	}
	avg := sum / n
	if math.Abs(avg-90000) > 90000*0.05 {
		t.Fatalf("walk mean %v not near new base 90000", avg)
	}
}

func TestNewTick_Shape(t *testing.T) {
	tk := NewTick("005930", 71234.567, mustTime())
	if tk.StockCode != "005930" {
		t.Errorf("stockCode: got %q", tk.StockCode)
	}
	if tk.Price != 71234.57 {
		t.Errorf("price not rounded to 2dp: %v", tk.Price)
	}
	if tk.Ts == "" {
		t.Error("ts empty")
	}
}
