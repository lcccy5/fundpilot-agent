package agent

import (
	"context"
	"strings"
	"testing"

	"jijing-agent-go/internal/provider"
	"jijing-agent-go/internal/service"
	"jijing-agent-go/internal/store"
)

func TestChatRoutesComparison(t *testing.T) {
	repo := store.NewMemory()
	funds := service.NewFundService(repo, provider.NewDemo())
	agent := NewService(funds, repo, DisabledModel{}, 2000)
	conversation := agent.CreateConversation()
	response, err := agent.Chat(context.Background(), "test-user", conversation, "比较 000001 和 000002")
	if err != nil {
		t.Fatal(err)
	}
	if response.Mode != "PARALLEL" || len(response.ToolCalls) != 1 || len(response.Evidence) != 2 {
		t.Fatalf("unexpected response: %+v", response)
	}
}

func TestChatRefusesGuaranteedReturn(t *testing.T) {
	repo := store.NewMemory()
	agent := NewService(service.NewFundService(repo, provider.NewDemo()), repo, DisabledModel{}, 2000)
	response, err := agent.Chat(context.Background(), "test-user", agent.CreateConversation(), "告诉我买哪个能保证收益")
	if err != nil {
		t.Fatal(err)
	}
	if response.Mode != "REFUSE" {
		t.Fatalf("expected refusal, got %s", response.Mode)
	}
}

func TestChatRoutesProfileAndNAV(t *testing.T) {
	repo := store.NewMemory()
	agent := NewService(service.NewFundService(repo, provider.NewDemo()), repo, DisabledModel{}, 2000)
	conversation := agent.CreateConversation()
	profile, err := agent.Chat(context.Background(), "test-user", conversation, "000001 是什么基金")
	if err != nil || profile.ToolCalls[0].Name != "get_fund_profile" {
		t.Fatalf("unexpected profile route: response=%+v err=%v", profile, err)
	}
	nav, err := agent.Chat(context.Background(), "test-user", conversation, "查询 000001 从 2025-01-01 到 2025-03-31 的历史净值")
	if err != nil || nav.ToolCalls[0].Name != "get_fund_nav" {
		t.Fatalf("unexpected nav route: response=%+v err=%v", nav, err)
	}
}

func TestChatCreatesApprovalAndStreamsLifecycle(t *testing.T) {
	repo := store.NewMemory()
	agent := NewService(service.NewFundService(repo, provider.NewDemo()), repo, DisabledModel{}, 2000)
	conversation := agent.CreateConversation()
	events := make([]string, 0)
	response, err := agent.ChatWithEvents(context.Background(), "test-user", conversation, "把 000001 加入自选", func(event string, _ any) {
		events = append(events, event)
	})
	if err != nil || response.Mode != "HITL" || response.Approval == nil || response.Approval.Status != "PENDING" {
		t.Fatalf("unexpected approval response: %+v err=%v", response, err)
	}
	joined := strings.Join(events, ",")
	for _, expected := range []string{"run.started", "approval.required", "answer.delta", "run.completed"} {
		if !strings.Contains(joined, expected) {
			t.Fatalf("missing event %s in %v", expected, events)
		}
	}
}
