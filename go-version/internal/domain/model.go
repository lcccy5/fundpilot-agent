package domain

import "time"

type Fund struct {
	Code              string    `json:"code"`
	Name              string    `json:"name"`
	FundType          string    `json:"fundType"`
	ManagementCompany string    `json:"managementCompany,omitempty"`
	FundManager       string    `json:"fundManager,omitempty"`
	EstablishedDate   string    `json:"establishedDate,omitempty"`
	Source            string    `json:"source"`
	SourceUpdatedAt   time.Time `json:"sourceUpdatedAt"`
	DataVersion       string    `json:"dataVersion"`
}

type NAVPoint struct {
	FundCode        string    `json:"fundCode"`
	Date            time.Time `json:"navDate"`
	UnitNAV         float64   `json:"unitNav"`
	AccumulatedNAV  float64   `json:"accumulatedNav"`
	Status          string    `json:"status"`
	Source          string    `json:"source"`
	SourceUpdatedAt time.Time `json:"sourceUpdatedAt"`
	DataVersion     string    `json:"dataVersion"`
}

// Quote represents the latest confirmed NAV and the optional intraday estimate.
// Estimated values are never mixed into historical confirmed NAV calculations.
type Quote struct {
	FundCode      string     `json:"fundCode"`
	Name          string     `json:"name"`
	NAVDate       string     `json:"navDate"`
	ConfirmedNAV  float64    `json:"confirmedNav"`
	EstimatedNAV  *float64   `json:"estimatedNav,omitempty"`
	EstimatedRate *float64   `json:"estimatedRate,omitempty"`
	EstimateTime  *time.Time `json:"estimateTime,omitempty"`
	Source        string     `json:"source"`
	Status        string     `json:"status"`
	Limitations   []string   `json:"limitations,omitempty"`
}

type Drawdown struct {
	Value        float64    `json:"value"`
	PeakDate     time.Time  `json:"peakDate"`
	TroughDate   time.Time  `json:"troughDate"`
	RecoveryDate *time.Time `json:"recoveryDate,omitempty"`
}

type Metrics struct {
	FundCode             string    `json:"fundCode"`
	From                 time.Time `json:"from"`
	To                   time.Time `json:"to"`
	PointCount           int       `json:"pointCount"`
	CumulativeReturn     float64   `json:"cumulativeReturn"`
	AnnualizedReturn     float64   `json:"annualizedReturn"`
	AnnualizedVolatility float64   `json:"annualizedVolatility"`
	SharpeRatio          *float64  `json:"sharpeRatio,omitempty"`
	MaxDrawdown          Drawdown  `json:"maxDrawdown"`
	AlgorithmVersion     string    `json:"algorithmVersion"`
	DataVersion          string    `json:"dataVersion"`
	Limitations          []string  `json:"limitations,omitempty"`
}

type Comparison struct {
	From       time.Time `json:"from"`
	To         time.Time `json:"to"`
	Items      []Metrics `json:"items"`
	Conclusion string    `json:"conclusion"`
}

type Evidence struct {
	ID          string    `json:"id"`
	Kind        string    `json:"kind"`
	Source      string    `json:"source"`
	Description string    `json:"description"`
	DataTime    time.Time `json:"dataTime"`
	DataVersion string    `json:"dataVersion"`
}

type ToolCall struct {
	Name       string         `json:"name"`
	Arguments  map[string]any `json:"arguments"`
	DurationMS int64          `json:"durationMs"`
	Success    bool           `json:"success"`
}

type AgentResponse struct {
	ConversationID string     `json:"conversationId"`
	RunID          string     `json:"runId"`
	Mode           string     `json:"mode"`
	Answer         string     `json:"answer"`
	ToolCalls      []ToolCall `json:"toolCalls,omitempty"`
	Evidence       []Evidence `json:"evidence,omitempty"`
	Limitations    []string   `json:"limitations,omitempty"`
	CreatedAt      time.Time  `json:"createdAt"`
	Approval       *Approval  `json:"approval,omitempty"`
}

type RunRecord struct {
	ID             string        `json:"id"`
	ConversationID string        `json:"conversationId"`
	Question       string        `json:"question"`
	Response       AgentResponse `json:"response"`
	DurationMS     int64         `json:"durationMs"`
	CreatedAt      time.Time     `json:"createdAt"`
}

type WatchlistItem struct {
	UserID    string    `json:"userId"`
	FundCode  string    `json:"fundCode"`
	CreatedAt time.Time `json:"createdAt"`
}

type Position struct {
	ID        string    `json:"id"`
	UserID    string    `json:"userId"`
	FundCode  string    `json:"fundCode"`
	Shares    float64   `json:"shares"`
	CostNAV   float64   `json:"costNav"`
	CreatedAt time.Time `json:"createdAt"`
	UpdatedAt time.Time `json:"updatedAt"`
}

type PortfolioItem struct {
	Position    Position `json:"position"`
	FundName    string   `json:"fundName"`
	LatestNAV   float64  `json:"latestNav"`
	MarketValue float64  `json:"marketValue"`
	Profit      float64  `json:"profit"`
	ProfitRate  float64  `json:"profitRate"`
	Weight      float64  `json:"weight"`
}

type PortfolioAnalysis struct {
	UserID           string          `json:"userId"`
	TotalMarketValue float64         `json:"totalMarketValue"`
	TotalCost        float64         `json:"totalCost"`
	TotalProfit      float64         `json:"totalProfit"`
	TotalProfitRate  float64         `json:"totalProfitRate"`
	Concentration    float64         `json:"concentration"`
	Items            []PortfolioItem `json:"items"`
	Limitations      []string        `json:"limitations"`
}

type KnowledgeDocument struct {
	ID        string    `json:"id"`
	UserID    string    `json:"userId"`
	FundCode  string    `json:"fundCode,omitempty"`
	Title     string    `json:"title"`
	SourceURL string    `json:"sourceUrl,omitempty"`
	Content   string    `json:"-"`
	Hash      string    `json:"hash"`
	CreatedAt time.Time `json:"createdAt"`
}

type KnowledgeChunk struct {
	ID         string  `json:"id"`
	DocumentID string  `json:"documentId"`
	FundCode   string  `json:"fundCode,omitempty"`
	Title      string  `json:"title"`
	SourceURL  string  `json:"sourceUrl,omitempty"`
	Ordinal    int     `json:"ordinal"`
	Content    string  `json:"content"`
	Score      float64 `json:"score"`
}

type Approval struct {
	ID             string         `json:"id"`
	UserID         string         `json:"userId"`
	ConversationID string         `json:"conversationId"`
	Action         string         `json:"action"`
	Arguments      map[string]any `json:"arguments"`
	Status         string         `json:"status"`
	CreatedAt      time.Time      `json:"createdAt"`
	DecidedAt      *time.Time     `json:"decidedAt,omitempty"`
}
