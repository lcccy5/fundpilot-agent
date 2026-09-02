package agent

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"jijing-agent-go/internal/domain"
	"jijing-agent-go/internal/knowledge"
	"jijing-agent-go/internal/observability"
	"jijing-agent-go/internal/service"
	"jijing-agent-go/internal/store"
)

type Service struct {
	funds      *service.FundService
	store      store.Repository
	model      Model
	maxMessage int
	clock      func() time.Time
	users      *service.UserService
	knowledge  *knowledge.Service
}

func NewService(funds *service.FundService, repo store.Repository, model Model, maxMessage int) *Service {
	return &Service{funds: funds, store: repo, model: model, maxMessage: maxMessage, users: service.NewUserService(repo, funds), knowledge: knowledge.NewService(repo), clock: func() time.Time { return time.Now().UTC() }}
}

type EventSink func(event string, data any)

func (s *Service) CreateConversation() string {
	id := newID("conv")
	_ = s.store.CreateConversation(id)
	return id
}

func (s *Service) Chat(ctx context.Context, userID, conversationID, question string) (domain.AgentResponse, error) {
	return s.ChatWithEvents(ctx, userID, conversationID, question, nil)
}

func (s *Service) ChatWithEvents(ctx context.Context, userID, conversationID, question string, sink EventSink) (domain.AgentResponse, error) {
	var eventMu sync.Mutex
	emit := func(event string, data any) {
		if sink != nil {
			eventMu.Lock()
			defer eventMu.Unlock()
			sink(event, data)
		}
	}
	started := s.clock()
	if conversationID == "" || !s.store.HasConversation(conversationID) {
		return domain.AgentResponse{}, fmt.Errorf("conversation not found")
	}
	question = strings.TrimSpace(question)
	if question == "" {
		return domain.AgentResponse{}, fmt.Errorf("message is required")
	}
	if len([]rune(question)) > s.maxMessage {
		return domain.AgentResponse{}, fmt.Errorf("message exceeds %d characters", s.maxMessage)
	}
	runID := newID("run")
	response := domain.AgentResponse{ConversationID: conversationID, RunID: runID, CreatedAt: started}
	emit("run.started", map[string]any{"runId": runID})

	if unsafeQuestion(question) {
		response.Mode = "REFUSE"
		response.Answer = "我不能承诺收益、代替你作出确定性买卖决定或执行交易。我可以基于历史数据比较收益、波动率和最大回撤，帮助你理解风险。"
		response.Limitations = []string{"仅提供信息分析，不构成投资建议"}
		return s.finish(question, response, started, emit), nil
	}
	if action, args, ok := requestedWrite(question); ok {
		approval, err := s.users.CreateApproval(userID, conversationID, action, args)
		if err != nil {
			emit("run.failed", map[string]any{"error": err.Error()})
			return domain.AgentResponse{}, err
		}
		response.Mode = "HITL"
		response.Approval = &approval
		response.Answer = "该操作会修改你的个人数据，已创建审批单。确认参数后调用审批接口批准或拒绝。"
		emit("approval.required", approval)
		return s.finish(question, response, started, emit), nil
	}
	if containsAny(question, "我的组合", "持仓分析", "组合分析", "组合报告") {
		call := PlannedCall{ID: "portfolio", Name: "analyze_portfolio", Arguments: map[string]any{}}
		planned, err := s.executePlan(ctx, userID, question, response, []PlannedCall{call}, emit)
		if err != nil {
			emit("run.failed", map[string]any{"error": err.Error()})
			return domain.AgentResponse{}, err
		}
		return s.finish(question, planned, started, emit), nil
	}
	if containsAny(question, "文档", "公告", "季报", "招募说明书", "知识库") {
		codes := fundCodes(question)
		fundCode := ""
		if len(codes) > 0 {
			fundCode = codes[0]
		}
		call := PlannedCall{ID: "knowledge", Name: "search_fund_documents", Arguments: map[string]any{"query": question, "fundCode": fundCode}}
		planned, err := s.executePlan(ctx, userID, question, response, []PlannedCall{call}, emit)
		if err != nil {
			emit("run.failed", map[string]any{"error": err.Error()})
			return domain.AgentResponse{}, err
		}
		return s.finish(question, planned, started, emit), nil
	}
	if s.model.Enabled() {
		if calls, err := s.model.Plan(ctx, question); err == nil && len(calls) > 0 {
			emit("plan.created", calls)
			planned, executeErr := s.executePlan(ctx, userID, question, response, calls, emit)
			if executeErr == nil {
				return s.finish(question, planned, started, emit), nil
			}
			emit("plan.fallback", map[string]any{"error": executeErr.Error()})
		}
	}
	codes := fundCodes(question)
	if len(codes) == 0 {
		response.Mode = "CLARIFY"
		response.Answer = "请提供一个或多个 6 位基金代码，例如：分析 000001，或比较 000001 和 000002。"
		return s.finish(question, response, started, emit), nil
	}
	from, to, err := questionDateRange(question, s.clock())
	if err != nil {
		return domain.AgentResponse{}, err
	}
	if len(codes) >= 2 || strings.Contains(question, "比较") || strings.Contains(question, "对比") {
		response.Mode = "PARALLEL"
		callStarted := time.Now()
		emit("tool.started", map[string]any{"name": "compare_funds", "arguments": map[string]any{"codes": codes}})
		comparison, err := s.funds.Compare(ctx, codes, from, to)
		response.ToolCalls = []domain.ToolCall{{Name: "compare_funds", Arguments: map[string]any{"codes": codes, "from": from.Format(time.DateOnly), "to": to.Format(time.DateOnly)}, DurationMS: time.Since(callStarted).Milliseconds(), Success: err == nil}}
		observability.Tool(err == nil)
		emit("tool.completed", response.ToolCalls[0])
		if err != nil {
			return domain.AgentResponse{}, err
		}
		parts := make([]string, 0, len(comparison.Items))
		for i, item := range comparison.Items {
			parts = append(parts, fmt.Sprintf("%s：累计收益 %.2f%%，年化波动率 %.2f%%，最大回撤 %.2f%% [%d]", item.FundCode, item.CumulativeReturn*100, item.AnnualizedVolatility*100, item.MaxDrawdown.Value*100, i+1))
			response.Evidence = append(response.Evidence, evidence(i+1, item.FundCode, item.DataVersion, item.To))
		}
		response.Answer = strings.Join(parts, "\n") + "\n" + comparison.Conclusion
	} else {
		code := codes[0]
		response.Mode = "DIRECT"
		callStarted := time.Now()
		switch {
		case containsAny(question, "资料", "经理", "公司", "是什么基金", "基本信息"):
			emit("tool.started", map[string]any{"name": "get_fund_profile", "arguments": map[string]any{"code": code}})
			fund, err := s.funds.Profile(ctx, code)
			response.ToolCalls = []domain.ToolCall{{Name: "get_fund_profile", Arguments: map[string]any{"code": code}, DurationMS: time.Since(callStarted).Milliseconds(), Success: err == nil}}
			observability.Tool(err == nil)
			emit("tool.completed", response.ToolCalls[0])
			if err != nil {
				return domain.AgentResponse{}, err
			}
			response.Answer = fmt.Sprintf("%s（%s）是%s，管理人为%s，基金经理为%s。[1]", fund.Name, fund.Code, fund.FundType, fund.ManagementCompany, fund.FundManager)
			response.Evidence = []domain.Evidence{evidence(1, code, fund.DataVersion, fund.SourceUpdatedAt)}
		case containsAny(question, "净值", "走势", "历史"):
			emit("tool.started", map[string]any{"name": "get_fund_nav", "arguments": map[string]any{"code": code}})
			points, err := s.funds.NAV(ctx, code, from, to)
			response.ToolCalls = []domain.ToolCall{{Name: "get_fund_nav", Arguments: map[string]any{"code": code, "from": from.Format(time.DateOnly), "to": to.Format(time.DateOnly)}, DurationMS: time.Since(callStarted).Milliseconds(), Success: err == nil}}
			observability.Tool(err == nil)
			emit("tool.completed", response.ToolCalls[0])
			if err != nil {
				return domain.AgentResponse{}, err
			}
			first, last := points[0], points[len(points)-1]
			change := last.AccumulatedNAV/first.AccumulatedNAV - 1
			response.Answer = fmt.Sprintf("基金 %s 在 %s 至 %s 共取得 %d 个净值点，最新累计净值 %.4f，区间变化 %.2f%%。[1]", code, first.Date.Format(time.DateOnly), last.Date.Format(time.DateOnly), len(points), last.AccumulatedNAV, change*100)
			response.Evidence = []domain.Evidence{evidence(1, code, last.DataVersion, last.Date)}
		default:
			emit("tool.started", map[string]any{"name": "calculate_fund_metrics", "arguments": map[string]any{"code": code}})
			metrics, err := s.funds.Metrics(ctx, code, from, to)
			response.ToolCalls = []domain.ToolCall{{Name: "calculate_fund_metrics", Arguments: map[string]any{"code": code, "from": from.Format(time.DateOnly), "to": to.Format(time.DateOnly)}, DurationMS: time.Since(callStarted).Milliseconds(), Success: err == nil}}
			observability.Tool(err == nil)
			emit("tool.completed", response.ToolCalls[0])
			if err != nil {
				return domain.AgentResponse{}, err
			}
			sharpe := "不可用"
			if metrics.SharpeRatio != nil {
				sharpe = fmt.Sprintf("%.2f", *metrics.SharpeRatio)
			}
			response.Answer = fmt.Sprintf("基金 %s 在 %s 至 %s 的累计收益为 %.2f%%，年化收益 %.2f%%，年化波动率 %.2f%%，最大回撤 %.2f%%，夏普比率 %s。[1]", code, metrics.From.Format(time.DateOnly), metrics.To.Format(time.DateOnly), metrics.CumulativeReturn*100, metrics.AnnualizedReturn*100, metrics.AnnualizedVolatility*100, metrics.MaxDrawdown.Value*100, sharpe)
			response.Evidence = []domain.Evidence{evidence(1, code, metrics.DataVersion, metrics.To)}
		}
	}
	response.Limitations = []string{"历史业绩不代表未来表现", "演示数据源只用于功能体验；接入真实 Provider 后才能用于真实分析"}
	if s.funds.ProviderName() != "demo-generated-data" {
		response.Limitations = response.Limitations[:1]
	}
	if s.model.Enabled() {
		if rewritten, err := s.model.Rewrite(ctx, question, response.Answer); err == nil {
			response.Answer = rewritten
		}
	}
	return s.finish(question, response, started, emit), nil
}

func (s *Service) Runs(limit int) []domain.RunRecord { return s.store.Runs(limit) }

type plannedToolResult struct {
	answer      string
	evidence    []domain.Evidence
	limitations []string
	approval    *domain.Approval
	call        domain.ToolCall
}

func (s *Service) executePlan(ctx context.Context, userID, question string, response domain.AgentResponse, calls []PlannedCall, emit EventSink) (domain.AgentResponse, error) {
	if len(calls) == 0 || len(calls) > 8 {
		return response, fmt.Errorf("plan must contain 1 to 8 tool calls")
	}
	tasks := make([]Task, 0, len(calls))
	for index, planned := range calls {
		if planned.ID == "" {
			planned.ID = fmt.Sprintf("tool_%d", index+1)
			calls[index].ID = planned.ID
		}
		tasks = append(tasks, Task{ID: planned.ID, Run: func(ctx context.Context) (any, error) {
			emit("tool.started", map[string]any{"id": planned.ID, "name": planned.Name, "arguments": planned.Arguments})
			started := time.Now()
			result, err := s.executeTool(ctx, userID, response.ConversationID, planned)
			result.call = domain.ToolCall{Name: planned.Name, Arguments: planned.Arguments, DurationMS: time.Since(started).Milliseconds(), Success: err == nil}
			observability.Tool(err == nil)
			emit("tool.completed", result.call)
			return result, err
		}})
	}
	results, err := ExecuteDAG(ctx, tasks, 4)
	if err != nil {
		return response, err
	}
	response.Mode = "DIRECT"
	if len(calls) > 1 {
		response.Mode = "PLAN_EXECUTE"
	}
	answers := make([]string, 0, len(calls))
	for _, planned := range calls {
		result := results[planned.ID]
		if result.Err != nil {
			return response, result.Err
		}
		toolResult := result.Value.(plannedToolResult)
		answers = append(answers, toolResult.answer)
		response.ToolCalls = append(response.ToolCalls, toolResult.call)
		response.Evidence = append(response.Evidence, toolResult.evidence...)
		response.Limitations = append(response.Limitations, toolResult.limitations...)
		if toolResult.approval != nil {
			response.Mode = "HITL"
			response.Approval = toolResult.approval
			emit("approval.required", *toolResult.approval)
		}
	}
	for index := range response.Evidence {
		response.Evidence[index].ID = strconv.Itoa(index + 1)
	}
	response.Answer = strings.Join(answers, "\n")
	if s.model.Enabled() && response.Approval == nil {
		if rewritten, err := s.model.Rewrite(ctx, question, response.Answer); err == nil {
			response.Answer = rewritten
		}
	}
	return response, nil
}

func (s *Service) executeTool(ctx context.Context, userID, conversationID string, call PlannedCall) (plannedToolResult, error) {
	now := s.clock()
	from, to, err := argumentsDateRange(call.Arguments, now)
	if err != nil {
		return plannedToolResult{}, err
	}
	switch call.Name {
	case "get_fund_profile":
		code := stringArg(call.Arguments, "code")
		fund, err := s.funds.Profile(ctx, code)
		if err != nil {
			return plannedToolResult{}, err
		}
		answer := fmt.Sprintf("%s（%s），类型：%s，管理人：%s，基金经理：%s。[证据]", fund.Name, fund.Code, emptyAsUnknown(fund.FundType), emptyAsUnknown(fund.ManagementCompany), emptyAsUnknown(fund.FundManager))
		return plannedToolResult{answer: answer, evidence: []domain.Evidence{evidence(1, code, fund.DataVersion, fund.SourceUpdatedAt)}}, nil
	case "get_fund_nav":
		code := stringArg(call.Arguments, "code")
		points, err := s.funds.NAV(ctx, code, from, to)
		if err != nil {
			return plannedToolResult{}, err
		}
		first, last := points[0], points[len(points)-1]
		answer := fmt.Sprintf("%s 至 %s 共 %d 个确认净值点，最新单位净值 %.4f、累计净值 %.4f，区间变化 %.2f%%。[证据]", first.Date.Format(time.DateOnly), last.Date.Format(time.DateOnly), len(points), last.UnitNAV, last.AccumulatedNAV, (last.AccumulatedNAV/first.AccumulatedNAV-1)*100)
		return plannedToolResult{answer: answer, evidence: []domain.Evidence{evidence(1, code, last.DataVersion, last.Date)}, limitations: []string{"只使用基金公司确认净值"}}, nil
	case "calculate_fund_metrics":
		code := stringArg(call.Arguments, "code")
		metrics, err := s.funds.Metrics(ctx, code, from, to)
		if err != nil {
			return plannedToolResult{}, err
		}
		sharpe := "不可用"
		if metrics.SharpeRatio != nil {
			sharpe = fmt.Sprintf("%.2f", *metrics.SharpeRatio)
		}
		answer := fmt.Sprintf("基金 %s：累计收益 %.2f%%，年化收益 %.2f%%，年化波动 %.2f%%，最大回撤 %.2f%%，夏普 %s。[证据]", code, metrics.CumulativeReturn*100, metrics.AnnualizedReturn*100, metrics.AnnualizedVolatility*100, metrics.MaxDrawdown.Value*100, sharpe)
		return plannedToolResult{answer: answer, evidence: []domain.Evidence{evidence(1, code, metrics.DataVersion, metrics.To)}, limitations: metrics.Limitations}, nil
	case "compare_funds":
		codes := stringSliceArg(call.Arguments, "codes")
		comparison, err := s.funds.Compare(ctx, codes, from, to)
		if err != nil {
			return plannedToolResult{}, err
		}
		parts := make([]string, 0, len(comparison.Items)+1)
		evidences := make([]domain.Evidence, 0, len(comparison.Items))
		for index, item := range comparison.Items {
			parts = append(parts, fmt.Sprintf("%s：收益 %.2f%%，波动 %.2f%%，回撤 %.2f%%。[证据]", item.FundCode, item.CumulativeReturn*100, item.AnnualizedVolatility*100, item.MaxDrawdown.Value*100))
			evidences = append(evidences, evidence(index+1, item.FundCode, item.DataVersion, item.To))
		}
		parts = append(parts, comparison.Conclusion)
		return plannedToolResult{answer: strings.Join(parts, "\n"), evidence: evidences, limitations: []string{"比较使用同一查询区间的确认净值"}}, nil
	case "analyze_portfolio":
		portfolio, err := s.users.Portfolio(ctx, userID)
		if err != nil {
			return plannedToolResult{}, err
		}
		if len(portfolio.Items) == 0 {
			return plannedToolResult{answer: "当前没有保存的持仓，请先添加持仓。"}, nil
		}
		answer := fmt.Sprintf("组合总市值 %.2f，总盈亏 %.2f（%.2f%%），集中度 HHI %.3f，共 %d 只基金。", portfolio.TotalMarketValue, portfolio.TotalProfit, portfolio.TotalProfitRate*100, portfolio.Concentration, len(portfolio.Items))
		return plannedToolResult{answer: answer, limitations: portfolio.Limitations}, nil
	case "search_fund_documents":
		query := stringArg(call.Arguments, "query")
		fundCode := stringArg(call.Arguments, "fundCode")
		chunks, err := s.knowledge.Search(userID, fundCode, query, 5)
		if err != nil {
			return plannedToolResult{}, err
		}
		if len(chunks) == 0 {
			return plannedToolResult{answer: "知识库中没有找到能够支持回答的文档证据。", limitations: []string{"没有证据时不生成文档结论"}}, nil
		}
		parts := make([]string, 0, len(chunks))
		evidences := make([]domain.Evidence, 0, len(chunks))
		for index, chunk := range chunks {
			parts = append(parts, fmt.Sprintf("《%s》第 %d 段：%s", chunk.Title, chunk.Ordinal+1, truncate(chunk.Content, 260)))
			evidences = append(evidences, domain.Evidence{ID: strconv.Itoa(index + 1), Kind: "DOCUMENT", Source: chunk.DocumentID, Description: chunk.Title + " 第 " + strconv.Itoa(chunk.Ordinal+1) + " 段", DataTime: now, DataVersion: chunk.DocumentID})
		}
		return plannedToolResult{answer: strings.Join(parts, "\n"), evidence: evidences, limitations: []string{"文档结论仅基于命中的原文片段"}}, nil
	case "add_watchlist":
		code := stringArg(call.Arguments, "code")
		approval, err := s.users.CreateApproval(userID, conversationID, "ADD_WATCHLIST", map[string]any{"fundCode": code})
		return plannedToolResult{answer: "已创建加入自选的审批单，批准后才会执行。", approval: &approval}, err
	case "save_position":
		code := stringArg(call.Arguments, "code")
		shares, okShares := numberArg(call.Arguments, "shares")
		costNAV, okCost := numberArg(call.Arguments, "costNav")
		if !okShares || !okCost || shares <= 0 || costNAV <= 0 {
			return plannedToolResult{}, fmt.Errorf("shares and costNav must be positive")
		}
		approval, err := s.users.CreateApproval(userID, conversationID, "SAVE_POSITION", map[string]any{"fundCode": code, "shares": shares, "costNav": costNAV})
		return plannedToolResult{answer: "已创建保存持仓的审批单，批准后才会执行。", approval: &approval}, err
	default:
		return plannedToolResult{}, fmt.Errorf("tool is not allowed: %s", call.Name)
	}
}

func (s *Service) finish(question string, response domain.AgentResponse, started time.Time, emit EventSink) domain.AgentResponse {
	observability.AgentRun()
	s.record(question, response, started)
	for start, runes := 0, []rune(response.Answer); start < len(runes); start += 24 {
		end := start + 24
		if end > len(runes) {
			end = len(runes)
		}
		emit("answer.delta", map[string]any{"delta": string(runes[start:end])})
	}
	emit("evidence.completed", response.Evidence)
	emit("run.completed", response)
	return response
}

func (s *Service) record(question string, response domain.AgentResponse, started time.Time) {
	_ = s.store.SaveRun(domain.RunRecord{ID: response.RunID, ConversationID: response.ConversationID, Question: question, Response: response, DurationMS: time.Since(started).Milliseconds(), CreatedAt: started})
}

func evidence(index int, code, version string, dataTime time.Time) domain.Evidence {
	return domain.Evidence{ID: fmt.Sprintf("%d", index), Kind: "FACT", Source: "fund-nav:" + code, Description: "基金确认净值及确定性指标计算", DataTime: dataTime, DataVersion: version}
}

func fundCodes(message string) []string {
	matches := regexp.MustCompile(`\b\d{6}\b`).FindAllString(message, -1)
	unique := map[string]struct{}{}
	for _, code := range matches {
		unique[code] = struct{}{}
	}
	result := make([]string, 0, len(unique))
	for code := range unique {
		result = append(result, code)
	}
	sort.Strings(result)
	return result
}

func unsafeQuestion(message string) bool {
	for _, phrase := range []string{"保证收益", "稳赚", "一定赚钱", "替我买", "帮我买", "自动交易", "确定买入", "梭哈"} {
		if strings.Contains(message, phrase) {
			return true
		}
	}
	return false
}

func questionDateRange(message string, now time.Time) (time.Time, time.Time, error) {
	matches := regexp.MustCompile(`\d{4}-\d{2}-\d{2}`).FindAllString(message, 2)
	if len(matches) == 0 {
		return now.AddDate(-1, 0, 0), now, nil
	}
	from, err := time.Parse(time.DateOnly, matches[0])
	if err != nil {
		return time.Time{}, time.Time{}, fmt.Errorf("日期必须是真实的 YYYY-MM-DD")
	}
	to := now
	if len(matches) == 2 {
		to, err = time.Parse(time.DateOnly, matches[1])
		if err != nil {
			return time.Time{}, time.Time{}, fmt.Errorf("日期必须是真实的 YYYY-MM-DD")
		}
	}
	if from.After(to) {
		return time.Time{}, time.Time{}, fmt.Errorf("开始日期不能晚于结束日期")
	}
	return from, to, nil
}

func containsAny(value string, candidates ...string) bool {
	for _, candidate := range candidates {
		if strings.Contains(value, candidate) {
			return true
		}
	}
	return false
}

func requestedWrite(message string) (string, map[string]any, bool) {
	codes := fundCodes(message)
	if len(codes) == 0 {
		return "", nil, false
	}
	if containsAny(message, "加入自选", "添加自选", "放入自选") {
		return "ADD_WATCHLIST", map[string]any{"fundCode": codes[0]}, true
	}
	if containsAny(message, "保存持仓", "记录持仓", "添加持仓") {
		sharesMatch := regexp.MustCompile(`([0-9]+(?:\.[0-9]+)?)\s*份`).FindStringSubmatch(message)
		costMatch := regexp.MustCompile(`成本(?:净值)?\s*([0-9]+(?:\.[0-9]+)?)`).FindStringSubmatch(message)
		if len(sharesMatch) == 2 && len(costMatch) == 2 {
			shares, _ := strconv.ParseFloat(sharesMatch[1], 64)
			costNAV, _ := strconv.ParseFloat(costMatch[1], 64)
			return "SAVE_POSITION", map[string]any{"fundCode": codes[0], "shares": shares, "costNav": costNAV}, true
		}
	}
	return "", nil, false
}

func argumentsDateRange(arguments map[string]any, now time.Time) (time.Time, time.Time, error) {
	from, to := now.AddDate(-1, 0, 0), now
	if value := stringArg(arguments, "from"); value != "" {
		parsed, err := time.Parse(time.DateOnly, value)
		if err != nil {
			return time.Time{}, time.Time{}, fmt.Errorf("from must use YYYY-MM-DD")
		}
		from = parsed
	}
	if value := stringArg(arguments, "to"); value != "" {
		parsed, err := time.Parse(time.DateOnly, value)
		if err != nil {
			return time.Time{}, time.Time{}, fmt.Errorf("to must use YYYY-MM-DD")
		}
		to = parsed
	}
	if from.After(to) {
		return time.Time{}, time.Time{}, fmt.Errorf("from must not be after to")
	}
	return from, to, nil
}

func stringArg(arguments map[string]any, key string) string {
	value, _ := arguments[key].(string)
	return strings.TrimSpace(value)
}

func stringSliceArg(arguments map[string]any, key string) []string {
	switch values := arguments[key].(type) {
	case []string:
		return values
	case []any:
		result := make([]string, 0, len(values))
		for _, value := range values {
			if text, ok := value.(string); ok {
				result = append(result, text)
			}
		}
		return result
	default:
		return nil
	}
}

func numberArg(arguments map[string]any, key string) (float64, bool) {
	value, ok := arguments[key].(float64)
	return value, ok
}

func emptyAsUnknown(value string) string {
	if strings.TrimSpace(value) == "" {
		return "未知"
	}
	return value
}

func truncate(value string, limit int) string {
	runes := []rune(strings.TrimSpace(value))
	if len(runes) <= limit {
		return string(runes)
	}
	return string(runes[:limit]) + "…"
}

func newID(prefix string) string {
	bytes := make([]byte, 8)
	_, _ = rand.Read(bytes)
	return prefix + "_" + hex.EncodeToString(bytes)
}
