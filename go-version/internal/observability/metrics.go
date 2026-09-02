package observability

import (
	"fmt"
	"sync/atomic"
)

var httpRequests atomic.Uint64
var httpFailures atomic.Uint64
var agentRuns atomic.Uint64
var toolCalls atomic.Uint64
var toolFailures atomic.Uint64
var providerCalls atomic.Uint64
var providerFailures atomic.Uint64

func HTTP(status int) {
	httpRequests.Add(1)
	if status >= 400 {
		httpFailures.Add(1)
	}
}

func AgentRun() { agentRuns.Add(1) }
func Tool(success bool) {
	toolCalls.Add(1)
	if !success {
		toolFailures.Add(1)
	}
}
func Provider(success bool) {
	providerCalls.Add(1)
	if !success {
		providerFailures.Add(1)
	}
}

func Prometheus() string {
	return fmt.Sprintf(`# HELP fund_agent_http_requests_total Total HTTP requests.
# TYPE fund_agent_http_requests_total counter
fund_agent_http_requests_total %d
# HELP fund_agent_http_failures_total HTTP responses with status >= 400.
# TYPE fund_agent_http_failures_total counter
fund_agent_http_failures_total %d
# HELP fund_agent_runs_total Completed Agent runs.
# TYPE fund_agent_runs_total counter
fund_agent_runs_total %d
# HELP fund_agent_tool_calls_total Agent tool calls.
# TYPE fund_agent_tool_calls_total counter
fund_agent_tool_calls_total %d
# HELP fund_agent_tool_failures_total Failed Agent tool calls.
# TYPE fund_agent_tool_failures_total counter
fund_agent_tool_failures_total %d
# HELP fund_agent_provider_calls_total External provider calls.
# TYPE fund_agent_provider_calls_total counter
fund_agent_provider_calls_total %d
# HELP fund_agent_provider_failures_total Failed external provider calls.
# TYPE fund_agent_provider_failures_total counter
fund_agent_provider_failures_total %d
`, httpRequests.Load(), httpFailures.Load(), agentRuns.Load(), toolCalls.Load(), toolFailures.Load(), providerCalls.Load(), providerFailures.Load())
}
