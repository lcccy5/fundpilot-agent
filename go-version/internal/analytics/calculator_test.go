package analytics

import (
	"math"
	"testing"
	"time"

	"jijing-agent-go/internal/domain"
)

func TestCalculate(t *testing.T) {
	dates := []string{"2025-01-02", "2025-01-03", "2025-01-06", "2025-01-07"}
	values := []float64{1, 1.1, .88, 1.2}
	points := make([]domain.NAVPoint, len(dates))
	for i := range dates {
		date, _ := time.Parse(time.DateOnly, dates[i])
		points[i] = domain.NAVPoint{Date: date, AccumulatedNAV: values[i], DataVersion: "test"}
	}
	metrics, err := Calculate("000001", points, 0)
	if err != nil {
		t.Fatal(err)
	}
	if math.Abs(metrics.CumulativeReturn-.2) > 1e-9 {
		t.Fatalf("unexpected return: %v", metrics.CumulativeReturn)
	}
	if math.Abs(metrics.MaxDrawdown.Value - -.2) > 1e-9 {
		t.Fatalf("unexpected drawdown: %v", metrics.MaxDrawdown.Value)
	}
}

func TestCalculateRejectsInsufficientData(t *testing.T) {
	_, err := Calculate("000001", []domain.NAVPoint{{AccumulatedNAV: 1}}, 0)
	if err != ErrInsufficientData {
		t.Fatalf("expected ErrInsufficientData, got %v", err)
	}
}

func TestMaxDrawdownKeepsOriginalPeakAfterLaterHigh(t *testing.T) {
	base, _ := time.Parse(time.DateOnly, "2025-01-01")
	values := []float64{1, .7, 1.2, 1.1}
	points := make([]domain.NAVPoint, len(values))
	for i, value := range values {
		points[i] = domain.NAVPoint{Date: base.AddDate(0, 0, i), AccumulatedNAV: value}
	}
	result := maxDrawdown(points)
	if !result.PeakDate.Equal(base) || !result.TroughDate.Equal(base.AddDate(0, 0, 1)) {
		t.Fatalf("unexpected drawdown period: %+v", result)
	}
}
