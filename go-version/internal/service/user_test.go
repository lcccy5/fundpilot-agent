package service

import (
	"context"
	"testing"

	"jijing-agent-go/internal/provider"
	"jijing-agent-go/internal/store"
)

func TestPortfolioAndApproval(t *testing.T) {
	repo := store.NewMemory()
	funds := NewFundService(repo, provider.NewDemo())
	users := NewUserService(repo, funds)
	position, err := users.SavePosition("user-a", "", "000001", 100, 1)
	if err != nil || position.ID == "" {
		t.Fatalf("save position failed: %+v err=%v", position, err)
	}
	portfolio, err := users.Portfolio(context.Background(), "user-a")
	if err != nil || len(portfolio.Items) != 1 || portfolio.TotalMarketValue <= 0 {
		t.Fatalf("unexpected portfolio: %+v err=%v", portfolio, err)
	}
	approval, err := users.CreateApproval("user-a", "conv", "ADD_WATCHLIST", map[string]any{"fundCode": "000002"})
	if err != nil {
		t.Fatal(err)
	}
	approved, err := users.DecideApproval(context.Background(), "user-a", approval.ID, "APPROVE")
	if err != nil || approved.Status != "APPROVED" {
		t.Fatalf("approval failed: %+v err=%v", approved, err)
	}
	items, _ := users.Watchlist("user-a")
	if len(items) != 1 || items[0].FundCode != "000002" {
		t.Fatalf("approval side effect missing: %+v", items)
	}
}
