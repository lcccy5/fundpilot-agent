package provider

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestHTTPProviderContract(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer secret" {
			t.Fatalf("missing authorization header")
		}
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/funds/000001":
			fmt.Fprint(w, `{"code":"000001","name":"契约基金","fundType":"混合型","managementCompany":"示例公司","fundManager":"示例经理","establishedDate":"2020-01-01","sourceUpdatedAt":"2025-03-31T00:00:00Z"}`)
		case "/funds/000001/nav":
			fmt.Fprint(w, `{"items":[{"navDate":"2025-03-31","unitNav":1.2,"accumulatedNav":1.5,"sourceUpdatedAt":"2025-03-31T00:00:00Z"}]}`)
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()
	source, err := NewHTTP(server.URL, "secret", time.Second)
	if err != nil {
		t.Fatal(err)
	}
	fund, err := source.Profile(context.Background(), "000001")
	if err != nil || fund.Name != "契约基金" {
		t.Fatalf("unexpected profile: %+v err=%v", fund, err)
	}
	from, _ := time.Parse(time.DateOnly, "2025-03-01")
	to, _ := time.Parse(time.DateOnly, "2025-03-31")
	points, err := source.NAV(context.Background(), "000001", from, to)
	if err != nil || len(points) != 1 || points[0].AccumulatedNAV != 1.5 {
		t.Fatalf("unexpected nav: %+v err=%v", points, err)
	}
}
