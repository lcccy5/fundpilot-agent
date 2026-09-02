package store

import (
	"errors"
	"sort"
	"sync"
	"time"

	"jijing-agent-go/internal/domain"
)

var ErrNotFound = errors.New("not found")

type Memory struct {
	mu            sync.RWMutex
	funds         map[string]domain.Fund
	nav           map[string][]domain.NAVPoint
	conversations map[string]time.Time
	runs          []domain.RunRecord
	watchlists    map[string]map[string]domain.WatchlistItem
	positions     map[string]map[string]domain.Position
	documents     map[string]domain.KnowledgeDocument
	chunks        map[string][]domain.KnowledgeChunk
	approvals     map[string]domain.Approval
}

func NewMemory() *Memory {
	return &Memory{funds: map[string]domain.Fund{}, nav: map[string][]domain.NAVPoint{}, conversations: map[string]time.Time{}, watchlists: map[string]map[string]domain.WatchlistItem{}, positions: map[string]map[string]domain.Position{}, documents: map[string]domain.KnowledgeDocument{}, chunks: map[string][]domain.KnowledgeChunk{}, approvals: map[string]domain.Approval{}}
}

func (m *Memory) SaveFund(fund domain.Fund) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.funds[fund.Code] = fund
	return nil
}

func (m *Memory) Fund(code string) (domain.Fund, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	fund, ok := m.funds[code]
	if !ok {
		return domain.Fund{}, ErrNotFound
	}
	return fund, nil
}

func (m *Memory) SaveNAV(code string, points []domain.NAVPoint) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	byDate := map[string]domain.NAVPoint{}
	for _, old := range m.nav[code] {
		byDate[old.Date.Format(time.DateOnly)] = old
	}
	for _, point := range points {
		byDate[point.Date.Format(time.DateOnly)] = point
	}
	merged := make([]domain.NAVPoint, 0, len(byDate))
	for _, point := range byDate {
		merged = append(merged, point)
	}
	sort.Slice(merged, func(i, j int) bool { return merged[i].Date.Before(merged[j].Date) })
	m.nav[code] = merged
	return nil
}

func (m *Memory) NAV(code string, from, to time.Time) []domain.NAVPoint {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make([]domain.NAVPoint, 0)
	for _, point := range m.nav[code] {
		if !point.Date.Before(from) && !point.Date.After(to) {
			result = append(result, point)
		}
	}
	return result
}

func (m *Memory) CreateConversation(id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.conversations[id] = time.Now().UTC()
	return nil
}

func (m *Memory) HasConversation(id string) bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	_, ok := m.conversations[id]
	return ok
}

func (m *Memory) SaveRun(run domain.RunRecord) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.runs = append(m.runs, run)
	return nil
}

func (m *Memory) AddWatchlist(item domain.WatchlistItem) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.watchlists[item.UserID] == nil {
		m.watchlists[item.UserID] = map[string]domain.WatchlistItem{}
	}
	m.watchlists[item.UserID][item.FundCode] = item
	return nil
}

func (m *Memory) RemoveWatchlist(userID, code string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.watchlists[userID], code)
	return nil
}

func (m *Memory) Watchlist(userID string) ([]domain.WatchlistItem, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make([]domain.WatchlistItem, 0, len(m.watchlists[userID]))
	for _, item := range m.watchlists[userID] {
		result = append(result, item)
	}
	sort.Slice(result, func(i, j int) bool { return result[i].CreatedAt.Before(result[j].CreatedAt) })
	return result, nil
}

func (m *Memory) SavePosition(position domain.Position) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.positions[position.UserID] == nil {
		m.positions[position.UserID] = map[string]domain.Position{}
	}
	m.positions[position.UserID][position.ID] = position
	return nil
}

func (m *Memory) DeletePosition(userID, id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.positions[userID], id)
	return nil
}

func (m *Memory) Positions(userID string) ([]domain.Position, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make([]domain.Position, 0, len(m.positions[userID]))
	for _, item := range m.positions[userID] {
		result = append(result, item)
	}
	sort.Slice(result, func(i, j int) bool { return result[i].CreatedAt.Before(result[j].CreatedAt) })
	return result, nil
}

func (m *Memory) SaveDocument(document domain.KnowledgeDocument, chunks []domain.KnowledgeChunk) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.documents[document.ID] = document
	m.chunks[document.ID] = append([]domain.KnowledgeChunk(nil), chunks...)
	return nil
}

func (m *Memory) Documents(userID string) ([]domain.KnowledgeDocument, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make([]domain.KnowledgeDocument, 0)
	for _, document := range m.documents {
		if document.UserID == userID || document.UserID == "public" {
			result = append(result, document)
		}
	}
	return result, nil
}

func (m *Memory) Chunks(userID, fundCode string) ([]domain.KnowledgeChunk, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make([]domain.KnowledgeChunk, 0)
	for id, chunks := range m.chunks {
		document := m.documents[id]
		if document.UserID != userID && document.UserID != "public" {
			continue
		}
		for _, chunk := range chunks {
			if fundCode == "" || chunk.FundCode == fundCode {
				result = append(result, chunk)
			}
		}
	}
	return result, nil
}

func (m *Memory) SaveApproval(approval domain.Approval) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.approvals[approval.ID] = approval
	return nil
}

func (m *Memory) Approval(userID, id string) (domain.Approval, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	approval, ok := m.approvals[id]
	if !ok || approval.UserID != userID {
		return domain.Approval{}, ErrNotFound
	}
	return approval, nil
}

func (m *Memory) Approvals(userID string) ([]domain.Approval, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make([]domain.Approval, 0)
	for _, approval := range m.approvals {
		if approval.UserID == userID {
			result = append(result, approval)
		}
	}
	return result, nil
}

func (m *Memory) Runs(limit int) []domain.RunRecord {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if limit <= 0 || limit > len(m.runs) {
		limit = len(m.runs)
	}
	result := make([]domain.RunRecord, limit)
	for i := 0; i < limit; i++ {
		result[i] = m.runs[len(m.runs)-1-i]
	}
	return result
}
