package store

import (
	"path/filepath"
	"testing"
	"time"

	"jijing-agent-go/internal/domain"
)

func TestSQLRepositoryPersistsCoreData(t *testing.T) {
	repo, err := OpenSQL("sqlite", "file:"+filepath.ToSlash(filepath.Join(t.TempDir(), "test.db"))+"?_pragma=busy_timeout(5000)")
	if err != nil {
		t.Fatal(err)
	}
	defer repo.Close()
	now := time.Now().UTC().Truncate(time.Second)
	fund := domain.Fund{Code: "000001", Name: "测试基金", Source: "test", SourceUpdatedAt: now, DataVersion: "v1"}
	if err := repo.SaveFund(fund); err != nil {
		t.Fatal(err)
	}
	loaded, err := repo.Fund("000001")
	if err != nil || loaded.Name != fund.Name {
		t.Fatalf("unexpected fund: %+v err=%v", loaded, err)
	}
	point := domain.NAVPoint{FundCode: "000001", Date: now, UnitNAV: 1.2, AccumulatedNAV: 1.5}
	if err := repo.SaveNAV("000001", []domain.NAVPoint{point}); err != nil {
		t.Fatal(err)
	}
	if points := repo.NAV("000001", now.Add(-time.Hour), now.Add(time.Hour)); len(points) != 1 {
		t.Fatalf("expected one NAV point, got %d", len(points))
	}
	if err := repo.CreateConversation("conv_test"); err != nil || !repo.HasConversation("conv_test") {
		t.Fatalf("conversation persistence failed: %v", err)
	}
}
