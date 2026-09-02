package service

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"sort"
	"sync"
	"time"

	"jijing-agent-go/internal/analytics"
	"jijing-agent-go/internal/cache"
	"jijing-agent-go/internal/domain"
	"jijing-agent-go/internal/observability"
	"jijing-agent-go/internal/provider"
	"jijing-agent-go/internal/store"
)

var ErrInvalidFundCode = errors.New("fund code must contain exactly 6 digits")

type FundService struct {
	store    store.Repository
	provider provider.FundDataProvider
	locks    sync.Map
	cache    cache.FundCache
}

func NewFundService(repo store.Repository, source provider.FundDataProvider) *FundService {
	return &FundService{store: repo, provider: source, cache: cache.NoOp{}}
}

func (s *FundService) WithCache(value cache.FundCache) *FundService {
	if value != nil {
		s.cache = value
	}
	return s
}

func (s *FundService) Profile(ctx context.Context, code string) (domain.Fund, error) {
	if !validCode(code) {
		return domain.Fund{}, ErrInvalidFundCode
	}
	if fund, ok := s.cache.GetFund(ctx, code); ok {
		return fund, nil
	}
	if fund, err := s.store.Fund(code); err == nil {
		s.cache.SetFund(ctx, fund)
		return fund, nil
	}
	if err := s.Sync(ctx, code, time.Now().AddDate(-1, 0, 0), time.Now()); err != nil {
		return domain.Fund{}, err
	}
	return s.store.Fund(code)
}

func (s *FundService) NAV(ctx context.Context, code string, from, to time.Time) ([]domain.NAVPoint, error) {
	if !validCode(code) {
		return nil, ErrInvalidFundCode
	}
	if from.After(to) {
		return nil, errors.New("from must not be after to")
	}
	if points, ok := s.cache.GetNAV(ctx, code, from, to); ok {
		return points, nil
	}
	points := s.store.NAV(code, from, to)
	if len(points) == 0 {
		if err := s.Sync(ctx, code, from, to); err != nil {
			return nil, err
		}
		points = s.store.NAV(code, from, to)
	}
	if len(points) == 0 {
		return nil, analytics.ErrInsufficientData
	}
	s.cache.SetNAV(ctx, code, from, to, points)
	return points, nil
}

func (s *FundService) Metrics(ctx context.Context, code string, from, to time.Time) (domain.Metrics, error) {
	points, err := s.NAV(ctx, code, from, to)
	if err != nil {
		return domain.Metrics{}, err
	}
	return analytics.Calculate(code, points, 0)
}

func (s *FundService) Quote(ctx context.Context, code string) (domain.Quote, error) {
	if !validCode(code) {
		return domain.Quote{}, ErrInvalidFundCode
	}
	return s.provider.Quote(ctx, code)
}

func (s *FundService) Compare(ctx context.Context, codes []string, from, to time.Time) (domain.Comparison, error) {
	if len(codes) < 2 || len(codes) > 10 {
		return domain.Comparison{}, errors.New("comparison requires 2 to 10 funds")
	}
	unique := map[string]struct{}{}
	for _, code := range codes {
		if !validCode(code) {
			return domain.Comparison{}, ErrInvalidFundCode
		}
		unique[code] = struct{}{}
	}
	if len(unique) < 2 {
		return domain.Comparison{}, errors.New("comparison requires at least 2 distinct funds")
	}

	type result struct {
		metrics domain.Metrics
		err     error
	}
	results := make(chan result, len(unique))
	var wg sync.WaitGroup
	for code := range unique {
		wg.Add(1)
		go func(code string) {
			defer wg.Done()
			metrics, err := s.Metrics(ctx, code, from, to)
			results <- result{metrics: metrics, err: err}
		}(code)
	}
	wg.Wait()
	close(results)
	items := make([]domain.Metrics, 0, len(unique))
	for result := range results {
		if result.err != nil {
			return domain.Comparison{}, result.err
		}
		items = append(items, result.metrics)
	}
	sort.Slice(items, func(i, j int) bool {
		if items[i].CumulativeReturn == items[j].CumulativeReturn {
			return items[i].FundCode < items[j].FundCode
		}
		return items[i].CumulativeReturn > items[j].CumulativeReturn
	})
	conclusion := fmt.Sprintf("在所选区间内，%s 的累计收益排名第一；风险仍需结合最大回撤和波动率综合判断。", items[0].FundCode)
	return domain.Comparison{From: from, To: to, Items: items, Conclusion: conclusion}, nil
}

func (s *FundService) Sync(ctx context.Context, code string, from, to time.Time) error {
	if !validCode(code) {
		return ErrInvalidFundCode
	}
	lockValue, _ := s.locks.LoadOrStore(code, &sync.Mutex{})
	lock := lockValue.(*sync.Mutex)
	lock.Lock()
	defer lock.Unlock()
	fund, err := s.provider.Profile(ctx, code)
	if err != nil {
		observability.Provider(false)
		return err
	}
	points, err := s.provider.NAV(ctx, code, from, to)
	if err != nil {
		observability.Provider(false)
		return err
	}
	observability.Provider(true)
	if len(points) == 0 {
		return analytics.ErrInsufficientData
	}
	if err := s.store.SaveFund(fund); err != nil {
		return err
	}
	if err := s.store.SaveNAV(code, points); err != nil {
		return err
	}
	s.cache.SetFund(ctx, fund)
	s.cache.SetNAV(ctx, code, from, to, points)
	return nil
}

func (s *FundService) ProviderName() string { return s.provider.Name() }

func validCode(code string) bool { return regexp.MustCompile(`^\d{6}$`).MatchString(code) }
