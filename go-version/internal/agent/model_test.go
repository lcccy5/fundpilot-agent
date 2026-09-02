package agent

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestCompatibleModelReturnsStructuredToolCalls(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer key" {
			t.Fatal("missing authorization")
		}
		if !strings.HasSuffix(r.URL.Path, "/chat/completions") {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"choices":[{"message":{"tool_calls":[{"id":"call_1","type":"function","function":{"name":"calculate_fund_metrics","arguments":"{\"code\":\"000001\"}"}}]}}]}`)
	}))
	defer server.Close()
	model := NewCompatibleModel(server.URL, "key", "test", time.Second)
	calls, err := model.Plan(context.Background(), "分析000001")
	if err != nil || len(calls) != 1 || calls[0].Name != "calculate_fund_metrics" || calls[0].Arguments["code"] != "000001" {
		t.Fatalf("unexpected calls: %+v err=%v", calls, err)
	}
}
