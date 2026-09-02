package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"jijing-agent-go/internal/agent"
	"jijing-agent-go/internal/cache"
	"jijing-agent-go/internal/config"
	"jijing-agent-go/internal/httpapi"
	"jijing-agent-go/internal/provider"
	"jijing-agent-go/internal/scheduler"
	"jijing-agent-go/internal/service"
	"jijing-agent-go/internal/store"
)

func main() {
	cfg := config.Load()
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))
	source := buildProvider(cfg)
	repo, closeStore := buildStore(cfg)
	defer closeStore()
	fundCache := buildCache(cfg)
	defer fundCache.Close()
	funds := service.NewFundService(repo, source).WithCache(fundCache)
	model := agent.NewCompatibleModel(cfg.ModelBaseURL, cfg.ModelAPIKey, cfg.ModelName, cfg.RequestTimeout)
	agentService := agent.NewService(funds, repo, model, cfg.MaxMessageLength)
	server := &http.Server{Addr: cfg.Addr, Handler: httpapi.NewRouter(funds, agentService, repo, cfg.AuthToken, cfg.DefaultUserID, cfg.TikaURL), ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 60 * time.Second}
	stop, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	scheduler.Start(stop, cfg.SyncInterval, cfg.DefaultUserID, repo, funds)

	go func() {
		slog.Info("server_started", "addr", cfg.Addr, "provider", source.Name(), "model_enabled", model.Enabled())
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("server_failed", "error", err)
			os.Exit(1)
		}
	}()

	<-stop.Done()
	ctx, shutdown := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdown()
	if err := server.Shutdown(ctx); err != nil {
		slog.Error("shutdown_failed", "error", err)
	}
	slog.Info("server_stopped")
}

func buildCache(cfg config.Config) cache.FundCache {
	if cfg.RedisAddress == "" {
		return cache.NoOp{}
	}
	value, err := cache.NewRedis(context.Background(), cfg.RedisAddress, cfg.RedisPassword, cfg.RedisDatabase, 10*time.Minute)
	if err != nil {
		slog.Warn("redis_unavailable_fallback", "error", err)
		return cache.NoOp{}
	}
	return value
}

func buildProvider(cfg config.Config) provider.FundDataProvider {
	if cfg.ProviderType == "http" {
		source, err := provider.NewHTTP(cfg.ProviderBaseURL, cfg.ProviderAPIKey, cfg.RequestTimeout)
		if err != nil {
			slog.Error("invalid_provider_config", "error", err)
			os.Exit(2)
		}
		return source
	}
	if cfg.ProviderType == "demo" {
		return provider.NewDemo()
	}
	return provider.NewEastMoney(cfg.RequestTimeout)
}

func buildStore(cfg config.Config) (store.Repository, func()) {
	if cfg.DatabaseDriver == "memory" {
		return store.NewMemory(), func() {}
	}
	if cfg.DatabaseDriver == "sqlite" {
		if err := os.MkdirAll(filepath.Join("data"), 0o755); err != nil {
			slog.Error("database_directory_failed", "error", err)
			os.Exit(2)
		}
	}
	repo, err := store.OpenSQL(cfg.DatabaseDriver, cfg.DatabaseDSN)
	if err != nil {
		slog.Error("database_startup_failed", "driver", cfg.DatabaseDriver, "error", err)
		os.Exit(2)
	}
	return repo, func() { _ = repo.Close() }
}
