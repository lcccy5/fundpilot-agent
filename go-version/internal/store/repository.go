package store

import (
	"time"

	"jijing-agent-go/internal/domain"
)

type Repository interface {
	SaveFund(domain.Fund) error
	Fund(string) (domain.Fund, error)
	SaveNAV(string, []domain.NAVPoint) error
	NAV(string, time.Time, time.Time) []domain.NAVPoint
	CreateConversation(string) error
	HasConversation(string) bool
	SaveRun(domain.RunRecord) error
	Runs(int) []domain.RunRecord
	AddWatchlist(domain.WatchlistItem) error
	RemoveWatchlist(string, string) error
	Watchlist(string) ([]domain.WatchlistItem, error)
	SavePosition(domain.Position) error
	DeletePosition(string, string) error
	Positions(string) ([]domain.Position, error)
	SaveDocument(domain.KnowledgeDocument, []domain.KnowledgeChunk) error
	Documents(string) ([]domain.KnowledgeDocument, error)
	Chunks(string, string) ([]domain.KnowledgeChunk, error)
	SaveApproval(domain.Approval) error
	Approval(string, string) (domain.Approval, error)
	Approvals(string) ([]domain.Approval, error)
}
