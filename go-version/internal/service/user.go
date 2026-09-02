package service

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"math"
	"time"

	"jijing-agent-go/internal/domain"
	"jijing-agent-go/internal/store"
)

type UserService struct {
	repo  store.Repository
	funds *FundService
}

func NewUserService(repo store.Repository, funds *FundService) *UserService {
	return &UserService{repo: repo, funds: funds}
}

func (s *UserService) AddWatchlist(userID, code string) error {
	if _, err := s.funds.Profile(context.Background(), code); err != nil {
		return err
	}
	return s.repo.AddWatchlist(domain.WatchlistItem{UserID: userID, FundCode: code, CreatedAt: time.Now().UTC()})
}

func (s *UserService) RemoveWatchlist(userID, code string) error {
	return s.repo.RemoveWatchlist(userID, code)
}

func (s *UserService) Watchlist(userID string) ([]domain.WatchlistItem, error) {
	return s.repo.Watchlist(userID)
}

func (s *UserService) SavePosition(userID, id, code string, shares, costNAV float64) (domain.Position, error) {
	if !validCode(code) {
		return domain.Position{}, ErrInvalidFundCode
	}
	if shares <= 0 || costNAV <= 0 || math.IsNaN(shares) || math.IsNaN(costNAV) {
		return domain.Position{}, errors.New("shares and costNav must be positive")
	}
	if _, err := s.funds.Profile(context.Background(), code); err != nil {
		return domain.Position{}, err
	}
	now := time.Now().UTC()
	if id == "" {
		id = randomID("pos")
	}
	position := domain.Position{ID: id, UserID: userID, FundCode: code, Shares: shares, CostNAV: costNAV, CreatedAt: now, UpdatedAt: now}
	if err := s.repo.SavePosition(position); err != nil {
		return domain.Position{}, err
	}
	return position, nil
}

func (s *UserService) DeletePosition(userID, id string) error {
	return s.repo.DeletePosition(userID, id)
}

func (s *UserService) Portfolio(ctx context.Context, userID string) (domain.PortfolioAnalysis, error) {
	positions, err := s.repo.Positions(userID)
	if err != nil {
		return domain.PortfolioAnalysis{}, err
	}
	items := make([]domain.PortfolioItem, 0, len(positions))
	totalValue, totalCost := 0.0, 0.0
	for _, position := range positions {
		quote, err := s.funds.Quote(ctx, position.FundCode)
		if err != nil {
			return domain.PortfolioAnalysis{}, err
		}
		fund, _ := s.funds.Profile(ctx, position.FundCode)
		value := position.Shares * quote.ConfirmedNAV
		cost := position.Shares * position.CostNAV
		items = append(items, domain.PortfolioItem{Position: position, FundName: fund.Name, LatestNAV: quote.ConfirmedNAV, MarketValue: value, Profit: value - cost, ProfitRate: value/cost - 1})
		totalValue += value
		totalCost += cost
	}
	concentration := 0.0
	for i := range items {
		if totalValue > 0 {
			items[i].Weight = items[i].MarketValue / totalValue
			concentration += items[i].Weight * items[i].Weight
		}
	}
	profitRate := 0.0
	if totalCost > 0 {
		profitRate = totalValue/totalCost - 1
	}
	return domain.PortfolioAnalysis{UserID: userID, TotalMarketValue: totalValue, TotalCost: totalCost, TotalProfit: totalValue - totalCost, TotalProfitRate: profitRate, Concentration: concentration, Items: items, Limitations: []string{"收益按最新确认净值估算，未计申赎费、分红方式和税费", "持仓数据仅属于当前用户"}}, nil
}

func (s *UserService) CreateApproval(userID, conversationID, action string, args map[string]any) (domain.Approval, error) {
	approval := domain.Approval{ID: randomID("approval"), UserID: userID, ConversationID: conversationID, Action: action, Arguments: args, Status: "PENDING", CreatedAt: time.Now().UTC()}
	return approval, s.repo.SaveApproval(approval)
}

func (s *UserService) DecideApproval(ctx context.Context, userID, id, decision string) (domain.Approval, error) {
	approval, err := s.repo.Approval(userID, id)
	if err != nil {
		return domain.Approval{}, err
	}
	if approval.Status != "PENDING" {
		return domain.Approval{}, errors.New("approval has already been decided")
	}
	now := time.Now().UTC()
	approval.DecidedAt = &now
	if decision == "REJECT" {
		approval.Status = "REJECTED"
		return approval, s.repo.SaveApproval(approval)
	}
	if decision != "APPROVE" {
		return domain.Approval{}, errors.New("decision must be APPROVE or REJECT")
	}
	switch approval.Action {
	case "ADD_WATCHLIST":
		code, _ := approval.Arguments["fundCode"].(string)
		if err := s.AddWatchlist(userID, code); err != nil {
			return domain.Approval{}, err
		}
	case "SAVE_POSITION":
		code, _ := approval.Arguments["fundCode"].(string)
		shares, _ := number(approval.Arguments["shares"])
		costNAV, _ := number(approval.Arguments["costNav"])
		if _, err := s.SavePosition(userID, "", code, shares, costNAV); err != nil {
			return domain.Approval{}, err
		}
	default:
		return domain.Approval{}, errors.New("unsupported approval action")
	}
	approval.Status = "APPROVED"
	return approval, s.repo.SaveApproval(approval)
}

func (s *UserService) Approvals(userID string) ([]domain.Approval, error) {
	return s.repo.Approvals(userID)
}

func number(value any) (float64, bool) {
	switch typed := value.(type) {
	case float64:
		return typed, true
	case int:
		return float64(typed), true
	default:
		return 0, false
	}
}

func randomID(prefix string) string {
	bytes := make([]byte, 8)
	_, _ = rand.Read(bytes)
	return prefix + "_" + hex.EncodeToString(bytes)
}
