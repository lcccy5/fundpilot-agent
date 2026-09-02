package cache

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
	"jijing-agent-go/internal/domain"
)

type FundCache interface {
	GetFund(context.Context, string) (domain.Fund, bool)
	SetFund(context.Context, domain.Fund)
	GetNAV(context.Context, string, time.Time, time.Time) ([]domain.NAVPoint, bool)
	SetNAV(context.Context, string, time.Time, time.Time, []domain.NAVPoint)
	Close() error
}

type NoOp struct{}

func (NoOp) GetFund(context.Context, string) (domain.Fund, bool) { return domain.Fund{}, false }
func (NoOp) SetFund(context.Context, domain.Fund)                {}
func (NoOp) GetNAV(context.Context, string, time.Time, time.Time) ([]domain.NAVPoint, bool) {
	return nil, false
}
func (NoOp) SetNAV(context.Context, string, time.Time, time.Time, []domain.NAVPoint) {}
func (NoOp) Close() error                                                            { return nil }

type Redis struct {
	client *redis.Client
	ttl    time.Duration
}

func NewRedis(ctx context.Context, address, password string, database int, ttl time.Duration) (*Redis, error) {
	client := redis.NewClient(&redis.Options{Addr: address, Password: password, DB: database, DialTimeout: 2 * time.Second, ReadTimeout: 2 * time.Second, WriteTimeout: 2 * time.Second})
	if err := client.Ping(ctx).Err(); err != nil {
		_ = client.Close()
		return nil, err
	}
	return &Redis{client: client, ttl: ttl}, nil
}

func (r *Redis) GetFund(ctx context.Context, code string) (domain.Fund, bool) {
	var value domain.Fund
	return value, r.get(ctx, "fund:v1:"+code, &value)
}

func (r *Redis) SetFund(ctx context.Context, fund domain.Fund) {
	r.set(ctx, "fund:v1:"+fund.Code, fund)
}

func (r *Redis) GetNAV(ctx context.Context, code string, from, to time.Time) ([]domain.NAVPoint, bool) {
	var value []domain.NAVPoint
	return value, r.get(ctx, navKey(code, from, to), &value)
}

func (r *Redis) SetNAV(ctx context.Context, code string, from, to time.Time, points []domain.NAVPoint) {
	r.set(ctx, navKey(code, from, to), points)
}

func (r *Redis) Close() error { return r.client.Close() }

func (r *Redis) get(ctx context.Context, key string, target any) bool {
	value, err := r.client.Get(ctx, key).Bytes()
	return err == nil && json.Unmarshal(value, target) == nil
}

func (r *Redis) set(ctx context.Context, key string, value any) {
	bytes, err := json.Marshal(value)
	if err == nil {
		_ = r.client.Set(ctx, key, bytes, r.ttl).Err()
	}
}

func navKey(code string, from, to time.Time) string {
	return fmt.Sprintf("nav:v1:%s:%s:%s", code, from.Format(time.DateOnly), to.Format(time.DateOnly))
}
