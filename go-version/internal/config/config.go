package config

import (
	"os"
	"strconv"
	"time"
)

type Config struct {
	Addr             string
	ProviderType     string
	ProviderBaseURL  string
	ProviderAPIKey   string
	ModelBaseURL     string
	ModelAPIKey      string
	ModelName        string
	RequestTimeout   time.Duration
	MaxMessageLength int
	DatabaseDriver   string
	DatabaseDSN      string
	AuthToken        string
	DefaultUserID    string
	SyncInterval     time.Duration
	RedisAddress     string
	RedisPassword    string
	RedisDatabase    int
	TikaURL          string
}

func Load() Config {
	return Config{
		Addr:             env("APP_ADDR", ":8080"),
		ProviderType:     env("FUND_PROVIDER_TYPE", "eastmoney"),
		ProviderBaseURL:  os.Getenv("FUND_PROVIDER_BASE_URL"),
		ProviderAPIKey:   os.Getenv("FUND_PROVIDER_API_KEY"),
		ModelBaseURL:     env("MODEL_BASE_URL", "https://api.openai.com/v1"),
		ModelAPIKey:      os.Getenv("MODEL_API_KEY"),
		ModelName:        env("MODEL_NAME", "gpt-4.1-mini"),
		RequestTimeout:   duration("REQUEST_TIMEOUT", 15*time.Second),
		MaxMessageLength: integer("MAX_MESSAGE_LENGTH", 2000),
		DatabaseDriver:   env("DATABASE_DRIVER", "sqlite"),
		DatabaseDSN:      env("DATABASE_DSN", "file:data/fund-agent.db?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)"),
		AuthToken:        os.Getenv("APP_AUTH_TOKEN"),
		DefaultUserID:    env("DEFAULT_USER_ID", "local-user"),
		SyncInterval:     duration("SYNC_INTERVAL", 12*time.Hour),
		RedisAddress:     os.Getenv("REDIS_ADDRESS"),
		RedisPassword:    os.Getenv("REDIS_PASSWORD"),
		RedisDatabase:    integerAllowZero("REDIS_DATABASE", 0),
		TikaURL:          os.Getenv("TIKA_URL"),
	}
}

func integerAllowZero(key string, fallback int) int {
	value, err := strconv.Atoi(os.Getenv(key))
	if err != nil || value < 0 {
		return fallback
	}
	return value
}

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func duration(key string, fallback time.Duration) time.Duration {
	value, err := time.ParseDuration(os.Getenv(key))
	if err != nil {
		return fallback
	}
	return value
}

func integer(key string, fallback int) int {
	value, err := strconv.Atoi(os.Getenv(key))
	if err != nil || value <= 0 {
		return fallback
	}
	return value
}
