package provider

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"net/url"
	"strings"
	"time"

	"jijing-agent-go/internal/domain"
)

var ErrFundNotFound = errors.New("fund not found")

type FundDataProvider interface {
	Profile(context.Context, string) (domain.Fund, error)
	NAV(context.Context, string, time.Time, time.Time) ([]domain.NAVPoint, error)
	Quote(context.Context, string) (domain.Quote, error)
	Name() string
}

type Demo struct{}

func NewDemo() Demo       { return Demo{} }
func (Demo) Name() string { return "demo-generated-data" }

var demoFunds = map[string]struct{ name, kind, manager string }{
	"000001": {"示例稳健混合", "混合型", "张明"},
	"000002": {"示例成长混合", "混合型", "李华"},
	"110022": {"示例消费指数", "指数型", "王宁"},
	"161725": {"示例科技指数", "指数型", "赵晨"},
}

func (Demo) Profile(_ context.Context, code string) (domain.Fund, error) {
	item, ok := demoFunds[code]
	if !ok {
		return domain.Fund{}, ErrFundNotFound
	}
	now := time.Now().UTC()
	return domain.Fund{Code: code, Name: item.name, FundType: item.kind, ManagementCompany: "示例基金管理有限公司", FundManager: item.manager, EstablishedDate: "2020-01-02", Source: "demo-generated-data", SourceUpdatedAt: now, DataVersion: now.Format("20060102")}, nil
}

func (d Demo) Quote(ctx context.Context, code string) (domain.Quote, error) {
	fund, err := d.Profile(ctx, code)
	if err != nil {
		return domain.Quote{}, err
	}
	today := time.Now().UTC()
	points, err := d.NAV(ctx, code, today.AddDate(0, 0, -10), today)
	if err != nil || len(points) == 0 {
		return domain.Quote{}, ErrFundNotFound
	}
	last := points[len(points)-1]
	estimate := last.AccumulatedNAV * 1.001
	rate := .1
	return domain.Quote{FundCode: code, Name: fund.Name, NAVDate: last.Date.Format(time.DateOnly), ConfirmedNAV: last.UnitNAV, EstimatedNAV: &estimate, EstimatedRate: &rate, EstimateTime: &today, Source: d.Name(), Status: "ESTIMATED", Limitations: []string{"演示估值，不可用于真实投资判断"}}, nil
}

func (Demo) NAV(ctx context.Context, code string, from, to time.Time) ([]domain.NAVPoint, error) {
	if _, ok := demoFunds[code]; !ok {
		return nil, ErrFundNotFound
	}
	seed := 0
	for _, char := range code {
		seed += int(char)
	}
	value := 1.0 + float64(seed%13)/100
	now := time.Now().UTC()
	points := make([]domain.NAVPoint, 0)
	index := 0
	for day := from; !day.After(to); day = day.AddDate(0, 0, 1) {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		if day.Weekday() == time.Saturday || day.Weekday() == time.Sunday {
			continue
		}
		wave := math.Sin(float64(index+seed)*.19) * .006
		trend := .00015 + float64(seed%7)*.000025
		shock := 0.0
		if index%67 == 0 && index > 0 {
			shock = -.025
		}
		value *= 1 + trend + wave + shock
		points = append(points, domain.NAVPoint{FundCode: code, Date: day, UnitNAV: round(value, 6), AccumulatedNAV: round(value, 6), Status: "CONFIRMED", Source: "demo-generated-data", SourceUpdatedAt: now, DataVersion: "demo-v1"})
		index++
	}
	return points, nil
}

type HTTP struct {
	baseURL string
	apiKey  string
	client  *http.Client
}

func NewHTTP(baseURL, apiKey string, timeout time.Duration) (*HTTP, error) {
	baseURL = strings.TrimRight(baseURL, "/")
	if _, err := url.ParseRequestURI(baseURL); err != nil || baseURL == "" {
		return nil, fmt.Errorf("invalid FUND_PROVIDER_BASE_URL")
	}
	return &HTTP{baseURL: baseURL, apiKey: apiKey, client: &http.Client{Timeout: timeout}}, nil
}

func (h *HTTP) Name() string { return "configured-http-api" }

func (h *HTTP) Quote(ctx context.Context, code string) (domain.Quote, error) {
	var response struct {
		FundCode      string     `json:"fundCode"`
		Name          string     `json:"name"`
		NAVDate       string     `json:"navDate"`
		ConfirmedNAV  float64    `json:"confirmedNav"`
		EstimatedNAV  *float64   `json:"estimatedNav"`
		EstimatedRate *float64   `json:"estimatedRate"`
		EstimateTime  *time.Time `json:"estimateTime"`
		Status        string     `json:"status"`
	}
	if err := h.get(ctx, "/funds/"+code+"/quote", &response); err != nil {
		return domain.Quote{}, err
	}
	return domain.Quote{FundCode: code, Name: response.Name, NAVDate: response.NAVDate, ConfirmedNAV: response.ConfirmedNAV, EstimatedNAV: response.EstimatedNAV, EstimatedRate: response.EstimatedRate, EstimateTime: response.EstimateTime, Source: h.Name(), Status: response.Status}, nil
}

func (h *HTTP) Profile(ctx context.Context, code string) (domain.Fund, error) {
	var response struct {
		Code, Name, FundType, ManagementCompany, FundManager, EstablishedDate string
		SourceUpdatedAt                                                       time.Time `json:"sourceUpdatedAt"`
	}
	if err := h.get(ctx, "/funds/"+code, &response); err != nil {
		return domain.Fund{}, err
	}
	if response.Name == "" {
		return domain.Fund{}, errors.New("provider returned empty fund name")
	}
	updated := response.SourceUpdatedAt
	if updated.IsZero() {
		updated = time.Now().UTC()
	}
	return domain.Fund{Code: code, Name: response.Name, FundType: response.FundType, ManagementCompany: response.ManagementCompany, FundManager: response.FundManager, EstablishedDate: response.EstablishedDate, Source: h.Name(), SourceUpdatedAt: updated, DataVersion: updated.Format("20060102T150405Z")}, nil
}

func (h *HTTP) NAV(ctx context.Context, code string, from, to time.Time) ([]domain.NAVPoint, error) {
	path := fmt.Sprintf("/funds/%s/nav?startDate=%s&endDate=%s", code, from.Format(time.DateOnly), to.Format(time.DateOnly))
	var response struct {
		Items []struct {
			NAVDate         string    `json:"navDate"`
			UnitNAV         float64   `json:"unitNav"`
			AccumulatedNAV  float64   `json:"accumulatedNav"`
			SourceUpdatedAt time.Time `json:"sourceUpdatedAt"`
		} `json:"items"`
	}
	if err := h.get(ctx, path, &response); err != nil {
		return nil, err
	}
	points := make([]domain.NAVPoint, 0, len(response.Items))
	for _, item := range response.Items {
		date, err := time.Parse(time.DateOnly, item.NAVDate)
		if err != nil || item.AccumulatedNAV <= 0 {
			continue
		}
		updated := item.SourceUpdatedAt
		if updated.IsZero() {
			updated = time.Now().UTC()
		}
		points = append(points, domain.NAVPoint{FundCode: code, Date: date, UnitNAV: item.UnitNAV, AccumulatedNAV: item.AccumulatedNAV, Status: "CONFIRMED", Source: h.Name(), SourceUpdatedAt: updated, DataVersion: updated.Format("20060102T150405Z")})
	}
	return points, nil
}

func (h *HTTP) get(ctx context.Context, path string, target any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, h.baseURL+path, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", "jijing-agent-go/1.0")
	if h.apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+h.apiKey)
	}
	response, err := h.client.Do(req)
	if err != nil {
		return fmt.Errorf("fund provider: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusNotFound {
		return ErrFundNotFound
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return fmt.Errorf("fund provider returned HTTP %d", response.StatusCode)
	}
	if err := json.NewDecoder(response.Body).Decode(target); err != nil {
		return fmt.Errorf("decode fund provider: %w", err)
	}
	return nil
}

func round(value float64, places int) float64 {
	factor := math.Pow10(places)
	return math.Round(value*factor) / factor
}
