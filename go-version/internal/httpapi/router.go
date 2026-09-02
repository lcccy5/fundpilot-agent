package httpapi

import (
	"context"
	"crypto/rand"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"jijing-agent-go/internal/agent"
	"jijing-agent-go/internal/analytics"
	"jijing-agent-go/internal/knowledge"
	"jijing-agent-go/internal/observability"
	"jijing-agent-go/internal/provider"
	"jijing-agent-go/internal/service"
	"jijing-agent-go/internal/store"
)

//go:embed web/*
var webFiles embed.FS

type API struct {
	funds     *service.FundService
	agent     *agent.Service
	repo      store.Repository
	users     *service.UserService
	knowledge *knowledge.Service
	tikaURL   string
}

func NewRouter(funds *service.FundService, agentService *agent.Service, repo store.Repository, authToken, defaultUserID, tikaURL string) *gin.Engine {
	gin.SetMode(gin.ReleaseMode)
	api := &API{funds: funds, agent: agentService, repo: repo, users: service.NewUserService(repo, funds), knowledge: knowledge.NewService(repo), tikaURL: tikaURL}
	router := gin.New()
	router.Use(gin.Recovery(), requestID(), authentication(authToken, defaultUserID), accessLog())
	web, _ := fs.Sub(webFiles, "web")
	router.StaticFS("/app", http.FS(web))
	router.GET("/", func(c *gin.Context) { c.Redirect(http.StatusTemporaryRedirect, "/app/") })
	router.GET("/healthz", api.health)
	router.GET("/readyz", api.health)
	router.GET("/metrics", func(c *gin.Context) {
		c.Data(http.StatusOK, "text/plain; version=0.0.4; charset=utf-8", []byte(observability.Prometheus()))
	})

	v1 := router.Group("/api/v1")
	v1.GET("/funds/:code", api.profile)
	v1.GET("/funds/:code/quote", api.quote)
	v1.GET("/funds/:code/nav", api.nav)
	v1.GET("/funds/:code/metrics", api.metrics)
	v1.POST("/fund-comparisons", api.compare)
	v1.POST("/funds/:code/sync", api.sync)
	v1.POST("/agent/conversations", api.createConversation)
	v1.POST("/agent/chat", api.chat)
	v1.POST("/agent/chat/stream", api.stream)
	v1.GET("/agent/runs", api.runs)
	v1.GET("/watchlist", api.watchlist)
	v1.POST("/watchlist/:code", api.addWatchlist)
	v1.DELETE("/watchlist/:code", api.removeWatchlist)
	v1.GET("/portfolio/positions", api.positions)
	v1.POST("/portfolio/positions", api.savePosition)
	v1.DELETE("/portfolio/positions/:id", api.deletePosition)
	v1.GET("/portfolio/analysis", api.portfolio)
	v1.POST("/knowledge/documents", api.ingestDocument)
	v1.POST("/knowledge/documents/upload", api.uploadDocument)
	v1.GET("/knowledge/documents", api.documents)
	v1.POST("/knowledge/search", api.searchKnowledge)
	v1.GET("/agent/approvals", api.approvals)
	v1.POST("/agent/approvals/:id/decision", api.decideApproval)
	return router
}

func (a *API) quote(c *gin.Context) {
	quote, err := a.funds.Quote(c.Request.Context(), c.Param("code"))
	respond(c, quote, err)
}

func (a *API) health(c *gin.Context) {
	success(c, gin.H{"status": "UP", "provider": a.funds.ProviderName(), "time": time.Now().UTC()})
}

func (a *API) profile(c *gin.Context) {
	fund, err := a.funds.Profile(c.Request.Context(), c.Param("code"))
	respond(c, fund, err)
}

func (a *API) nav(c *gin.Context) {
	from, to, err := dateRange(c)
	if err != nil {
		failure(c, http.StatusBadRequest, "INVALID_DATE_RANGE", err.Error())
		return
	}
	points, err := a.funds.NAV(c.Request.Context(), c.Param("code"), from, to)
	respond(c, gin.H{"fundCode": c.Param("code"), "from": from, "to": to, "items": points, "count": len(points)}, err)
}

func (a *API) metrics(c *gin.Context) {
	from, to, err := dateRange(c)
	if err != nil {
		failure(c, http.StatusBadRequest, "INVALID_DATE_RANGE", err.Error())
		return
	}
	metrics, err := a.funds.Metrics(c.Request.Context(), c.Param("code"), from, to)
	respond(c, metrics, err)
}

func (a *API) compare(c *gin.Context) {
	var request struct {
		Codes []string `json:"codes" binding:"required"`
		From  string   `json:"from"`
		To    string   `json:"to"`
	}
	if err := c.ShouldBindJSON(&request); err != nil {
		failure(c, http.StatusBadRequest, "INVALID_ARGUMENT", err.Error())
		return
	}
	from, to, err := parseDateRange(request.From, request.To)
	if err != nil {
		failure(c, http.StatusBadRequest, "INVALID_DATE_RANGE", err.Error())
		return
	}
	comparison, err := a.funds.Compare(c.Request.Context(), request.Codes, from, to)
	respond(c, comparison, err)
}

func (a *API) sync(c *gin.Context) {
	from, to, err := dateRange(c)
	if err != nil {
		failure(c, http.StatusBadRequest, "INVALID_DATE_RANGE", err.Error())
		return
	}
	err = a.funds.Sync(c.Request.Context(), c.Param("code"), from, to)
	respond(c, gin.H{"fundCode": c.Param("code"), "from": from, "to": to, "status": "SYNCED"}, err)
}

func (a *API) createConversation(c *gin.Context) {
	success(c, gin.H{"conversationId": a.agent.CreateConversation(), "createdAt": time.Now().UTC()})
}

type chatRequest struct {
	ConversationID string `json:"conversationId" binding:"required"`
	Message        string `json:"message" binding:"required"`
}

func (a *API) chat(c *gin.Context) {
	var request chatRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		failure(c, http.StatusBadRequest, "INVALID_ARGUMENT", err.Error())
		return
	}
	response, err := a.agent.Chat(c.Request.Context(), c.GetString("userId"), request.ConversationID, request.Message)
	respond(c, response, err)
}

func (a *API) stream(c *gin.Context) {
	var request chatRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		failure(c, http.StatusBadRequest, "INVALID_ARGUMENT", err.Error())
		return
	}
	c.Header("Content-Type", "text/event-stream")
	c.Header("Cache-Control", "no-cache")
	c.Header("Connection", "keep-alive")
	c.Header("X-Accel-Buffering", "no")
	_, err := a.agent.ChatWithEvents(c.Request.Context(), c.GetString("userId"), request.ConversationID, request.Message, func(event string, data any) {
		writeEvent(c, event, data)
	})
	if err != nil {
		writeEvent(c, "run.failed", gin.H{"error": err.Error()})
	}
}

func (a *API) runs(c *gin.Context) {
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))
	if limit > 100 {
		limit = 100
	}
	success(c, a.agent.Runs(limit))
}

func (a *API) watchlist(c *gin.Context) {
	items, err := a.users.Watchlist(c.GetString("userId"))
	respond(c, items, err)
}

func (a *API) addWatchlist(c *gin.Context) {
	err := a.users.AddWatchlist(c.GetString("userId"), c.Param("code"))
	respond(c, gin.H{"fundCode": c.Param("code"), "status": "ADDED"}, err)
}

func (a *API) removeWatchlist(c *gin.Context) {
	err := a.users.RemoveWatchlist(c.GetString("userId"), c.Param("code"))
	respond(c, gin.H{"fundCode": c.Param("code"), "status": "REMOVED"}, err)
}

func (a *API) positions(c *gin.Context) {
	items, err := a.repo.Positions(c.GetString("userId"))
	respond(c, items, err)
}

func (a *API) savePosition(c *gin.Context) {
	var request struct {
		ID       string  `json:"id"`
		FundCode string  `json:"fundCode" binding:"required"`
		Shares   float64 `json:"shares" binding:"required"`
		CostNAV  float64 `json:"costNav" binding:"required"`
	}
	if err := c.ShouldBindJSON(&request); err != nil {
		failure(c, http.StatusBadRequest, "INVALID_ARGUMENT", err.Error())
		return
	}
	position, err := a.users.SavePosition(c.GetString("userId"), request.ID, request.FundCode, request.Shares, request.CostNAV)
	respond(c, position, err)
}

func (a *API) deletePosition(c *gin.Context) {
	err := a.users.DeletePosition(c.GetString("userId"), c.Param("id"))
	respond(c, gin.H{"id": c.Param("id"), "status": "DELETED"}, err)
}

func (a *API) portfolio(c *gin.Context) {
	analysis, err := a.users.Portfolio(c.Request.Context(), c.GetString("userId"))
	respond(c, analysis, err)
}

func (a *API) ingestDocument(c *gin.Context) {
	var request struct {
		FundCode  string `json:"fundCode"`
		Title     string `json:"title" binding:"required"`
		SourceURL string `json:"sourceUrl"`
		Content   string `json:"content" binding:"required"`
		Public    bool   `json:"public"`
	}
	if err := c.ShouldBindJSON(&request); err != nil {
		failure(c, http.StatusBadRequest, "INVALID_ARGUMENT", err.Error())
		return
	}
	userID := c.GetString("userId")
	if request.Public {
		userID = "public"
	}
	document, err := a.knowledge.Ingest(userID, request.FundCode, request.Title, request.SourceURL, request.Content)
	respond(c, document, err)
}

func (a *API) uploadDocument(c *gin.Context) {
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 10<<20)
	file, header, err := c.Request.FormFile("file")
	if err != nil {
		failure(c, http.StatusBadRequest, "INVALID_FILE", "file is required and must not exceed 10 MB")
		return
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, (10<<20)+1))
	if err != nil || len(data) > 10<<20 {
		failure(c, http.StatusBadRequest, "INVALID_FILE", "file must not exceed 10 MB")
		return
	}
	content, err := knowledge.ParseUpload(c.Request.Context(), header.Filename, header.Header.Get("Content-Type"), data, a.tikaURL)
	if err != nil {
		failure(c, http.StatusUnprocessableEntity, "DOCUMENT_PARSE_FAILED", err.Error())
		return
	}
	title := strings.TrimSpace(c.PostForm("title"))
	if title == "" {
		title = header.Filename
	}
	document, err := a.knowledge.Ingest(c.GetString("userId"), c.PostForm("fundCode"), title, c.PostForm("sourceUrl"), content)
	respond(c, document, err)
}

func (a *API) documents(c *gin.Context) {
	documents, err := a.knowledge.Documents(c.GetString("userId"))
	respond(c, documents, err)
}

func (a *API) searchKnowledge(c *gin.Context) {
	var request struct {
		FundCode string `json:"fundCode"`
		Query    string `json:"query" binding:"required"`
		Limit    int    `json:"limit"`
	}
	if err := c.ShouldBindJSON(&request); err != nil {
		failure(c, http.StatusBadRequest, "INVALID_ARGUMENT", err.Error())
		return
	}
	results, err := a.knowledge.Search(c.GetString("userId"), request.FundCode, request.Query, request.Limit)
	respond(c, results, err)
}

func (a *API) approvals(c *gin.Context) {
	approvals, err := a.users.Approvals(c.GetString("userId"))
	respond(c, approvals, err)
}

func (a *API) decideApproval(c *gin.Context) {
	var request struct {
		Decision string `json:"decision" binding:"required"`
	}
	if err := c.ShouldBindJSON(&request); err != nil {
		failure(c, http.StatusBadRequest, "INVALID_ARGUMENT", err.Error())
		return
	}
	approval, err := a.users.DecideApproval(c.Request.Context(), c.GetString("userId"), c.Param("id"), strings.ToUpper(request.Decision))
	respond(c, approval, err)
}

func dateRange(c *gin.Context) (time.Time, time.Time, error) {
	return parseDateRange(c.Query("from"), c.Query("to"))
}

func parseDateRange(fromText, toText string) (time.Time, time.Time, error) {
	to := time.Now().UTC()
	from := to.AddDate(-1, 0, 0)
	var err error
	if strings.TrimSpace(toText) != "" {
		to, err = time.Parse(time.DateOnly, toText)
		if err != nil {
			return time.Time{}, time.Time{}, fmt.Errorf("to must use YYYY-MM-DD")
		}
	}
	if strings.TrimSpace(fromText) != "" {
		from, err = time.Parse(time.DateOnly, fromText)
		if err != nil {
			return time.Time{}, time.Time{}, fmt.Errorf("from must use YYYY-MM-DD")
		}
	}
	if from.After(to) {
		return time.Time{}, time.Time{}, fmt.Errorf("from must not be after to")
	}
	if to.Sub(from) > 10*365*24*time.Hour {
		return time.Time{}, time.Time{}, fmt.Errorf("date range must not exceed 10 years")
	}
	return from, to, nil
}

func respond(c *gin.Context, data any, err error) {
	if err == nil {
		success(c, data)
		return
	}
	status, code := http.StatusInternalServerError, "INTERNAL_ERROR"
	switch {
	case errors.Is(err, provider.ErrFundNotFound), errors.Is(err, store.ErrNotFound):
		status, code = http.StatusNotFound, "FUND_NOT_FOUND"
	case errors.Is(err, service.ErrInvalidFundCode):
		status, code = http.StatusBadRequest, "INVALID_FUND_CODE"
	case errors.Is(err, analytics.ErrInsufficientData):
		status, code = http.StatusUnprocessableEntity, "NAV_DATA_NOT_READY"
	case strings.Contains(err.Error(), "not found"):
		status, code = http.StatusNotFound, "NOT_FOUND"
	case strings.Contains(err.Error(), "requires") || strings.Contains(err.Error(), "must") || strings.Contains(err.Error(), "message"):
		status, code = http.StatusBadRequest, "INVALID_ARGUMENT"
	}
	failure(c, status, code, err.Error())
}

func success(c *gin.Context, data any) {
	c.JSON(http.StatusOK, gin.H{"success": true, "requestId": c.GetString("requestId"), "data": data})
}

func failure(c *gin.Context, status int, code, message string) {
	c.AbortWithStatusJSON(status, gin.H{"success": false, "requestId": c.GetString("requestId"), "error": gin.H{"code": code, "message": message}})
}

func writeEvent(c *gin.Context, event string, data any) {
	bytes, _ := json.Marshal(data)
	_, _ = fmt.Fprintf(c.Writer, "event: %s\ndata: %s\n\n", event, bytes)
	c.Writer.Flush()
}

func requestID() gin.HandlerFunc {
	return func(c *gin.Context) {
		id := c.GetHeader("X-Request-ID")
		if id == "" {
			bytes := make([]byte, 8)
			_, _ = rand.Read(bytes)
			id = hex.EncodeToString(bytes)
		}
		c.Set("requestId", id)
		c.Header("X-Request-ID", id)
		c.Next()
	}
}

func accessLog() gin.HandlerFunc {
	return func(c *gin.Context) {
		started := time.Now()
		c.Next()
		observability.HTTP(c.Writer.Status())
		slog.InfoContext(context.Background(), "http_request", "method", c.Request.Method, "path", c.Request.URL.Path, "status", c.Writer.Status(), "duration_ms", time.Since(started).Milliseconds(), "request_id", c.GetString("requestId"))
	}
}

func authentication(token, defaultUserID string) gin.HandlerFunc {
	return func(c *gin.Context) {
		if c.Request.URL.Path == "/" || c.Request.URL.Path == "/healthz" || c.Request.URL.Path == "/readyz" || strings.HasPrefix(c.Request.URL.Path, "/app/") {
			c.Set("userId", defaultUserID)
			c.Next()
			return
		}
		userID := defaultUserID
		if token != "" {
			if c.GetHeader("Authorization") != "Bearer "+token {
				failure(c, http.StatusUnauthorized, "UNAUTHORIZED", "missing or invalid bearer token")
				return
			}
		} else if headerUser := strings.TrimSpace(c.GetHeader("X-User-ID")); headerUser != "" {
			// X-User-ID is available only in unauthenticated local-development mode.
			userID = headerUser
		}
		if len(userID) > 64 {
			failure(c, http.StatusBadRequest, "INVALID_USER_ID", "user ID is too long")
			return
		}
		c.Set("userId", userID)
		c.Next()
	}
}
