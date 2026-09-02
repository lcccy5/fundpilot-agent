package analytics

import (
	"errors"
	"math"
	"sort"
	"time"

	"jijing-agent-go/internal/domain"
)

const AlgorithmVersion = "fund-metrics-go-v1"

var ErrInsufficientData = errors.New("at least two valid NAV points are required")

func Calculate(code string, points []domain.NAVPoint, riskFreeRate float64) (domain.Metrics, error) {
	series := normalize(points)
	if len(series) < 2 {
		return domain.Metrics{}, ErrInsufficientData
	}
	first, last := series[0], series[len(series)-1]
	cumulative := last.AccumulatedNAV/first.AccumulatedNAV - 1
	days := last.Date.Sub(first.Date).Hours() / 24
	annualized := cumulative
	if days > 0 && 1+cumulative > 0 {
		annualized = math.Pow(1+cumulative, 365/days) - 1
	}

	returns := make([]float64, 0, len(series)-1)
	for i := 1; i < len(series); i++ {
		returns = append(returns, series[i].AccumulatedNAV/series[i-1].AccumulatedNAV-1)
	}
	volatility := sampleStdDev(returns) * math.Sqrt(252)
	var sharpe *float64
	if volatility > 0 {
		value := (annualized - riskFreeRate) / volatility
		sharpe = &value
	}

	drawdown := maxDrawdown(series)
	limitations := []string{"历史业绩不代表未来表现", "指标基于已提供的确认净值计算"}
	return domain.Metrics{
		FundCode: code, From: first.Date, To: last.Date, PointCount: len(series),
		CumulativeReturn: cumulative, AnnualizedReturn: annualized,
		AnnualizedVolatility: volatility, SharpeRatio: sharpe, MaxDrawdown: drawdown,
		AlgorithmVersion: AlgorithmVersion, DataVersion: last.DataVersion,
		Limitations: limitations,
	}, nil
}

func normalize(points []domain.NAVPoint) []domain.NAVPoint {
	valid := make([]domain.NAVPoint, 0, len(points))
	for _, point := range points {
		if point.AccumulatedNAV > 0 {
			valid = append(valid, point)
		}
	}
	sort.Slice(valid, func(i, j int) bool { return valid[i].Date.Before(valid[j].Date) })
	dedup := valid[:0]
	for _, point := range valid {
		if len(dedup) > 0 && dedup[len(dedup)-1].Date.Equal(point.Date) {
			dedup[len(dedup)-1] = point
			continue
		}
		dedup = append(dedup, point)
	}
	return dedup
}

func sampleStdDev(values []float64) float64 {
	if len(values) < 2 {
		return 0
	}
	mean := 0.0
	for _, value := range values {
		mean += value
	}
	mean /= float64(len(values))
	sum := 0.0
	for _, value := range values {
		delta := value - mean
		sum += delta * delta
	}
	return math.Sqrt(sum / float64(len(values)-1))
}

func maxDrawdown(points []domain.NAVPoint) domain.Drawdown {
	peakIndex, drawdownPeakIndex, troughIndex := 0, 0, 0
	maxValue, peak := 0.0, points[0].AccumulatedNAV
	for i := 1; i < len(points); i++ {
		if points[i].AccumulatedNAV > peak {
			peak = points[i].AccumulatedNAV
			peakIndex = i
		}
		value := points[i].AccumulatedNAV/peak - 1
		if value < maxValue {
			maxValue = value
			drawdownPeakIndex = peakIndex
			troughIndex = i
		}
	}
	var recovery *time.Time
	peakNAV := points[drawdownPeakIndex].AccumulatedNAV
	for i := troughIndex + 1; i < len(points); i++ {
		if points[i].AccumulatedNAV >= peakNAV {
			date := points[i].Date
			recovery = &date
			break
		}
	}
	return domain.Drawdown{Value: maxValue, PeakDate: points[drawdownPeakIndex].Date, TroughDate: points[troughIndex].Date, RecoveryDate: recovery}
}
