package scheduler

import (
	"context"
	"log/slog"
	"time"

	"jijing-agent-go/internal/service"
	"jijing-agent-go/internal/store"
)

func Start(ctx context.Context, interval time.Duration, userID string, repo store.Repository, funds *service.FundService) {
	if interval <= 0 {
		return
	}
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				items, err := repo.Watchlist(userID)
				if err != nil {
					slog.Error("scheduled_sync_list_failed", "error", err)
					continue
				}
				for _, item := range items {
					jobCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
					err := funds.Sync(jobCtx, item.FundCode, time.Now().AddDate(0, -2, 0), time.Now())
					cancel()
					if err != nil {
						slog.Warn("scheduled_sync_failed", "fund_code", item.FundCode, "error", err)
					}
				}
			}
		}
	}()
}
