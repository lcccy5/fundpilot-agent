package provider

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"jijing-agent-go/internal/domain"
)

// EastMoney integrates the public pages used by Eastmoney's own fund website.
// The endpoints are undocumented, so callers should keep the configurable HTTP
// provider as a fallback and monitor contract changes.
type EastMoney struct {
	client *http.Client
}

func NewEastMoney(timeout time.Duration) *EastMoney {
	return &EastMoney{client: &http.Client{Timeout: timeout}}
}

func (*EastMoney) Name() string { return "eastmoney-public-web" }

type eastMoneyQuote struct {
	Code     string `json:"fundcode"`
	Name     string `json:"name"`
	NAVDate  string `json:"jzrq"`
	NAV      string `json:"dwjz"`
	Estimate string `json:"gsz"`
	Rate     string `json:"gszzl"`
	Time     string `json:"gztime"`
}

func (e *EastMoney) Quote(ctx context.Context, code string) (domain.Quote, error) {
	endpoint := "https://fundgz.1234567.com.cn/js/" + url.PathEscape(code) + ".js?rt=" + strconv.FormatInt(time.Now().UnixMilli(), 10)
	body, status, err := e.get(ctx, endpoint, "https://fund.eastmoney.com/")
	if err != nil {
		return domain.Quote{}, err
	}
	if status == http.StatusNotFound || strings.TrimSpace(string(body)) == "" {
		return domain.Quote{}, ErrFundNotFound
	}
	text := strings.TrimSpace(strings.TrimPrefix(string(body), "\ufeff"))
	if !strings.HasPrefix(text, "jsonpgz(") {
		return e.quoteFromDetails(ctx, code)
	}
	start, end := strings.IndexByte(text, '{'), strings.LastIndexByte(text, '}')
	if start < 0 || end <= start {
		return e.quoteFromDetails(ctx, code)
	}
	var raw eastMoneyQuote
	if err := json.Unmarshal([]byte(text[start:end+1]), &raw); err != nil {
		return e.quoteFromDetails(ctx, code)
	}
	if raw.Code == "" || raw.Name == "" {
		return e.quoteFromDetails(ctx, code)
	}
	confirmed, err := strconv.ParseFloat(raw.NAV, 64)
	if err != nil {
		return domain.Quote{}, fmt.Errorf("invalid confirmed NAV: %w", err)
	}
	var estimate, rate *float64
	if value, err := strconv.ParseFloat(raw.Estimate, 64); err == nil {
		estimate = &value
	}
	if value, err := strconv.ParseFloat(raw.Rate, 64); err == nil {
		rate = &value
	}
	var estimateTime *time.Time
	if value, err := time.ParseInLocation("2006-01-02 15:04", raw.Time, time.Local); err == nil {
		estimateTime = &value
	}
	statusText := "CONFIRMED"
	if estimate != nil {
		statusText = "ESTIMATED"
	}
	return domain.Quote{FundCode: raw.Code, Name: raw.Name, NAVDate: raw.NAVDate, ConfirmedNAV: confirmed, EstimatedNAV: estimate, EstimatedRate: rate, EstimateTime: estimateTime, Source: e.Name(), Status: statusText, Limitations: []string{"盘中估值不是基金公司确认净值，仅供参考", "接口来自公开网页，可能因上游调整而暂时不可用"}}, nil
}

func (e *EastMoney) quoteFromDetails(ctx context.Context, code string) (domain.Quote, error) {
	endpoint := "https://fund.eastmoney.com/pingzhongdata/" + url.PathEscape(code) + ".js?v=" + strconv.FormatInt(time.Now().UnixMilli(), 10)
	body, status, err := e.get(ctx, endpoint, "https://fund.eastmoney.com/"+code+".html")
	if err != nil {
		return domain.Quote{}, err
	}
	if status != http.StatusOK {
		return domain.Quote{}, ErrFundNotFound
	}
	text := strings.TrimPrefix(string(body), "\ufeff")
	name := extractQuotedVariable(text, "fS_name")
	trendJSON := extractJSONVariable(text, "Data_netWorthTrend")
	if name == "" || trendJSON == "" {
		return domain.Quote{}, ErrFundNotFound
	}
	var trend []struct {
		Timestamp int64   `json:"x"`
		NAV       float64 `json:"y"`
	}
	if err := json.Unmarshal([]byte(trendJSON), &trend); err != nil || len(trend) == 0 {
		return domain.Quote{}, fmt.Errorf("decode eastmoney detail trend: %w", err)
	}
	last := trend[len(trend)-1]
	date := time.UnixMilli(last.Timestamp).In(time.Local)
	return domain.Quote{FundCode: code, Name: name, NAVDate: date.Format(time.DateOnly), ConfirmedNAV: last.NAV, Source: e.Name(), Status: "CONFIRMED", Limitations: []string{"盘中估值源当前不可用，返回最近一个基金公司确认净值", "公开网页接口可能因上游调整而暂时不可用"}}, nil
}

func extractQuotedVariable(text, name string) string {
	prefix := "var " + name
	start := strings.Index(text, prefix)
	if start < 0 {
		return ""
	}
	remainder := text[start+len(prefix):]
	first := strings.IndexByte(remainder, '"')
	if first < 0 {
		return ""
	}
	second := strings.IndexByte(remainder[first+1:], '"')
	if second < 0 {
		return ""
	}
	return remainder[first+1 : first+1+second]
}

func extractJSONVariable(text, name string) string {
	prefix := "var " + name
	start := strings.Index(text, prefix)
	if start < 0 {
		return ""
	}
	remainder := text[start+len(prefix):]
	equals := strings.IndexByte(remainder, '=')
	semicolon := strings.IndexByte(remainder, ';')
	if equals < 0 || semicolon <= equals {
		return ""
	}
	return strings.TrimSpace(remainder[equals+1 : semicolon])
}

func (e *EastMoney) Profile(ctx context.Context, code string) (domain.Fund, error) {
	quote, err := e.Quote(ctx, code)
	if err != nil {
		return domain.Fund{}, err
	}
	updated := time.Now().UTC()
	if quote.EstimateTime != nil {
		updated = quote.EstimateTime.UTC()
	}
	return domain.Fund{Code: code, Name: quote.Name, FundType: "未知", Source: e.Name(), SourceUpdatedAt: updated, DataVersion: updated.Format("20060102T150405Z")}, nil
}

func (e *EastMoney) NAV(ctx context.Context, code string, from, to time.Time) ([]domain.NAVPoint, error) {
	const pageSize = 100
	points := make([]domain.NAVPoint, 0, 260)
	for page := 1; page <= 100; page++ {
		query := url.Values{"fundCode": {code}, "pageIndex": {strconv.Itoa(page)}, "pageSize": {strconv.Itoa(pageSize)}, "startDate": {from.Format(time.DateOnly)}, "endDate": {to.Format(time.DateOnly)}}
		endpoint := "https://api.fund.eastmoney.com/f10/lsjz?" + query.Encode()
		body, status, err := e.get(ctx, endpoint, "https://fundf10.eastmoney.com/jjjz_"+code+".html")
		if err != nil {
			return nil, err
		}
		if status != http.StatusOK {
			return nil, fmt.Errorf("eastmoney NAV returned HTTP %d", status)
		}
		var response struct {
			ErrCode int `json:"ErrCode"`
			Data    *struct {
				TotalCount int `json:"TotalCount"`
				Items      []struct {
					Date        string `json:"FSRQ"`
					Unit        string `json:"DWJZ"`
					Accumulated string `json:"LJJZ"`
				} `json:"LSJZList"`
			} `json:"Data"`
		}
		if err := json.Unmarshal(body, &response); err != nil {
			return nil, fmt.Errorf("decode eastmoney NAV: %w", err)
		}
		if response.ErrCode != 0 || response.Data == nil {
			return nil, fmt.Errorf("eastmoney NAV contract error: %d", response.ErrCode)
		}
		for _, item := range response.Data.Items {
			date, dateErr := time.Parse(time.DateOnly, item.Date)
			unit, unitErr := strconv.ParseFloat(item.Unit, 64)
			accumulated, accumulatedErr := strconv.ParseFloat(item.Accumulated, 64)
			if dateErr != nil || unitErr != nil || accumulatedErr != nil || accumulated <= 0 {
				continue
			}
			points = append(points, domain.NAVPoint{FundCode: code, Date: date, UnitNAV: unit, AccumulatedNAV: accumulated, Status: "CONFIRMED", Source: e.Name(), SourceUpdatedAt: time.Now().UTC(), DataVersion: "eastmoney-" + to.Format("20060102")})
		}
		if len(response.Data.Items) == 0 || (response.Data.TotalCount > 0 && len(points) >= response.Data.TotalCount) {
			break
		}
	}
	if len(points) == 0 {
		return nil, ErrFundNotFound
	}
	return points, nil
}

func (e *EastMoney) get(ctx context.Context, endpoint, referer string) ([]byte, int, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, 0, err
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 fund-agent-go/1.0")
	req.Header.Set("Referer", referer)
	response, err := e.client.Do(req)
	if err != nil {
		return nil, 0, fmt.Errorf("eastmoney request: %w", err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, 8<<20))
	if err != nil {
		return nil, response.StatusCode, err
	}
	return body, response.StatusCode, nil
}
