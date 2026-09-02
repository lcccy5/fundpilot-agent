package agent

import (
	"context"
	"testing"
)

func TestExecuteDAGAndRejectCycle(t *testing.T) {
	tasks := []Task{{ID: "a", Run: func(context.Context) (any, error) { return 1, nil }}, {ID: "b", DependsOn: []string{"a"}, Run: func(context.Context) (any, error) { return 2, nil }}}
	results, err := ExecuteDAG(context.Background(), tasks, 2)
	if err != nil || len(results) != 2 {
		t.Fatalf("unexpected results: %+v err=%v", results, err)
	}
	_, err = ExecuteDAG(context.Background(), []Task{{ID: "a", DependsOn: []string{"b"}, Run: tasks[0].Run}, {ID: "b", DependsOn: []string{"a"}, Run: tasks[0].Run}}, 2)
	if err == nil {
		t.Fatal("expected cycle error")
	}
}
