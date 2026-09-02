package knowledge

import (
	"strings"
	"testing"

	"jijing-agent-go/internal/store"
)

func TestIngestAndSearchIsTenantIsolated(t *testing.T) {
	repo := store.NewMemory()
	service := NewService(repo)
	_, err := service.Ingest("user-a", "000001", "基金季报", "", strings.Repeat("该基金主要持仓集中在消费行业，基金经理关注长期价值。", 20))
	if err != nil {
		t.Fatal(err)
	}
	results, err := service.Search("user-a", "000001", "消费行业持仓", 5)
	if err != nil || len(results) == 0 {
		t.Fatalf("expected search results, got=%d err=%v", len(results), err)
	}
	isolated, err := service.Search("user-b", "000001", "消费行业持仓", 5)
	if err != nil || len(isolated) != 0 {
		t.Fatalf("private chunks leaked: %+v err=%v", isolated, err)
	}
}
