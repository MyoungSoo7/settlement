package quote

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"sync"
	"time"
)

// PollingSource periodically queries the existing market-service series API to
// discover a real "latest close" for a stock, and uses that as the base price
// for the (still-simulated) walk. It layers on top of a SimulatedSource: the
// tick shape stays identical, only the base price is grounded in real data.
//
// This keeps the MVP runnable with zero external deps by default (use
// SimulatedSource directly), while giving a clear, working path to real-ish
// numbers when MARKET_BASE_URL points at a live market-service.
type PollingSource struct {
	*SimulatedSource

	baseURL      string
	client       *http.Client
	pollInterval time.Duration
	log          *slog.Logger

	mu       sync.Mutex
	lastPoll map[string]time.Time
}

// seriesResponse mirrors market-service StockController.SeriesResponse.
type seriesResponse struct {
	StockCode string        `json:"stockCode"`
	Name      string        `json:"name"`
	Market    string        `json:"market"`
	Points    []seriesPoint `json:"points"`
}

type seriesPoint struct {
	BaseDate   string  `json:"baseDate"`
	ClosePrice float64 `json:"closePrice"`
}

// NewPollingSource wraps a SimulatedSource, refreshing the base price from
// market-service at most once per pollInterval per stock code.
func NewPollingSource(sim *SimulatedSource, baseURL string, pollInterval time.Duration, log *slog.Logger) *PollingSource {
	if log == nil {
		log = slog.Default()
	}
	return &PollingSource{
		SimulatedSource: sim,
		baseURL:         baseURL,
		client:          &http.Client{Timeout: 5 * time.Second},
		pollInterval:    pollInterval,
		log:             log,
		lastPoll:        make(map[string]time.Time),
	}
}

// Name implements QuoteSource.
func (p *PollingSource) Name() string { return "polling" }

// BasePrice fetches the latest close from market-service (rate-limited per
// code) and feeds it into the underlying simulated walk. On any error it falls
// back to the simulated source's base, so the stream never dies.
func (p *PollingSource) BasePrice(stockCode string, fallback float64) float64 {
	if p.shouldPoll(stockCode) {
		if latest, ok := p.fetchLatestClose(context.Background(), stockCode); ok {
			p.SetBase(stockCode, latest)
			return latest
		}
	}
	return p.SimulatedSource.BasePrice(stockCode, fallback)
}

func (p *PollingSource) shouldPoll(stockCode string) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	last, ok := p.lastPoll[stockCode]
	if !ok || time.Since(last) >= p.pollInterval {
		p.lastPoll[stockCode] = time.Now()
		return true
	}
	return false
}

func (p *PollingSource) fetchLatestClose(ctx context.Context, stockCode string) (float64, bool) {
	// from = 30 days ago so we always get at least one point.
	from := time.Now().AddDate(0, 0, -30).Format("2006-01-02")
	u := fmt.Sprintf("%s/api/market/stocks/%s/series?from=%s",
		p.baseURL, url.PathEscape(stockCode), from)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		p.log.Warn("polling: build request failed", "err", err, "code", stockCode)
		return 0, false
	}
	resp, err := p.client.Do(req)
	if err != nil {
		p.log.Warn("polling: request failed", "err", err, "code", stockCode)
		return 0, false
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		p.log.Warn("polling: non-200", "status", resp.StatusCode, "code", stockCode)
		return 0, false
	}

	var sr seriesResponse
	if err := json.NewDecoder(resp.Body).Decode(&sr); err != nil {
		p.log.Warn("polling: decode failed", "err", err, "code", stockCode)
		return 0, false
	}
	if len(sr.Points) == 0 {
		return 0, false
	}
	// Latest point = last in the (ascending-date) series.
	latest := sr.Points[len(sr.Points)-1].ClosePrice
	if latest <= 0 {
		return 0, false
	}
	p.log.Info("polling: refreshed base price", "code", stockCode, "close", latest)
	return latest, true
}
