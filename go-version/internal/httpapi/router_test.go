package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"jijing-agent-go/internal/agent"
	"jijing-agent-go/internal/provider"
	"jijing-agent-go/internal/service"
	"jijing-agent-go/internal/store"
)

func testRouter() http.Handler {
	repo := store.NewMemory()
	funds := service.NewFundService(repo, provider.NewDemo())
	return NewRouter(funds, agent.NewService(funds, repo, agent.DisabledModel{}, 2000), repo, "", "test-user", "")
}

func TestFundMetricsEndpoint(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "/api/v1/funds/000001/metrics?from=2025-01-01&to=2025-12-31", nil)
	response := httptest.NewRecorder()
	testRouter().ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", response.Code, response.Body.String())
	}
	var body struct {
		Success bool `json:"success"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil || !body.Success {
		t.Fatalf("invalid response: %s", response.Body.String())
	}
}

func TestInvalidFundCode(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "/api/v1/funds/abc", nil)
	response := httptest.NewRecorder()
	testRouter().ServeHTTP(response, request)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", response.Code)
	}
}
