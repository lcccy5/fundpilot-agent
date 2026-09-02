package store

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"time"

	_ "github.com/go-sql-driver/mysql"
	_ "modernc.org/sqlite"

	"jijing-agent-go/internal/domain"
)

type SQL struct {
	db *sql.DB
}

func OpenSQL(driver, dsn string) (*SQL, error) {
	db, err := sql.Open(driver, dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(20)
	db.SetMaxIdleConns(5)
	db.SetConnMaxLifetime(30 * time.Minute)
	store := &SQL{db: db}
	if err := db.Ping(); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("connect database: %w", err)
	}
	if err := store.migrate(); err != nil {
		_ = db.Close()
		return nil, err
	}
	return store, nil
}

func (s *SQL) Close() error { return s.db.Close() }

func (s *SQL) migrate() error {
	statements := []string{
		`CREATE TABLE IF NOT EXISTS fund (code VARCHAR(6) PRIMARY KEY, payload TEXT NOT NULL, updated_at VARCHAR(40) NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS fund_nav (fund_code VARCHAR(6) NOT NULL, nav_date VARCHAR(10) NOT NULL, payload TEXT NOT NULL, PRIMARY KEY (fund_code, nav_date))`,
		`CREATE TABLE IF NOT EXISTS agent_conversation (id VARCHAR(64) PRIMARY KEY, created_at VARCHAR(40) NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS agent_run (id VARCHAR(64) PRIMARY KEY, conversation_id VARCHAR(64) NOT NULL, payload TEXT NOT NULL, created_at VARCHAR(40) NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS watchlist (user_id VARCHAR(64) NOT NULL, fund_code VARCHAR(6) NOT NULL, created_at VARCHAR(40) NOT NULL, PRIMARY KEY (user_id, fund_code))`,
		`CREATE TABLE IF NOT EXISTS portfolio_position (id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64) NOT NULL, payload TEXT NOT NULL, updated_at VARCHAR(40) NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS knowledge_document (id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64) NOT NULL, fund_code VARCHAR(6), payload TEXT NOT NULL, created_at VARCHAR(40) NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS knowledge_chunk (id VARCHAR(64) PRIMARY KEY, document_id VARCHAR(64) NOT NULL, fund_code VARCHAR(6), payload TEXT NOT NULL, ordinal_no INTEGER NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS agent_approval (id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64) NOT NULL, status VARCHAR(20) NOT NULL, payload TEXT NOT NULL, created_at VARCHAR(40) NOT NULL)`,
		`CREATE INDEX IF NOT EXISTS idx_nav_code_date ON fund_nav(fund_code, nav_date)`,
		`CREATE INDEX IF NOT EXISTS idx_position_user ON portfolio_position(user_id)`,
		`CREATE INDEX IF NOT EXISTS idx_document_user_fund ON knowledge_document(user_id, fund_code)`,
		`CREATE INDEX IF NOT EXISTS idx_chunk_document ON knowledge_chunk(document_id)`,
	}
	for _, statement := range statements {
		if _, err := s.db.Exec(statement); err != nil {
			// MySQL does not support IF NOT EXISTS for indexes on older versions.
			if len(statement) >= 12 && statement[:12] == "CREATE INDEX" {
				continue
			}
			return fmt.Errorf("database migration: %w", err)
		}
	}
	return nil
}

func (s *SQL) SaveFund(fund domain.Fund) error {
	return s.replace("fund", "code", fund.Code, `INSERT INTO fund(code,payload,updated_at) VALUES(?,?,?)`, fund.Code, marshal(fund), nowText())
}

func (s *SQL) Fund(code string) (domain.Fund, error) {
	var payload string
	if err := s.db.QueryRow(`SELECT payload FROM fund WHERE code=?`, code).Scan(&payload); err != nil {
		return domain.Fund{}, mapNotFound(err)
	}
	var value domain.Fund
	return value, json.Unmarshal([]byte(payload), &value)
}

func (s *SQL) SaveNAV(code string, points []domain.NAVPoint) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, point := range points {
		date := point.Date.Format(time.DateOnly)
		if _, err := tx.Exec(`DELETE FROM fund_nav WHERE fund_code=? AND nav_date=?`, code, date); err != nil {
			return err
		}
		if _, err := tx.Exec(`INSERT INTO fund_nav(fund_code,nav_date,payload) VALUES(?,?,?)`, code, date, marshal(point)); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (s *SQL) NAV(code string, from, to time.Time) []domain.NAVPoint {
	rows, err := s.db.Query(`SELECT payload FROM fund_nav WHERE fund_code=? AND nav_date>=? AND nav_date<=? ORDER BY nav_date`, code, from.Format(time.DateOnly), to.Format(time.DateOnly))
	if err != nil {
		return nil
	}
	defer rows.Close()
	result := make([]domain.NAVPoint, 0)
	for rows.Next() {
		var payload string
		var point domain.NAVPoint
		if rows.Scan(&payload) == nil && json.Unmarshal([]byte(payload), &point) == nil {
			result = append(result, point)
		}
	}
	return result
}

func (s *SQL) CreateConversation(id string) error {
	_, err := s.db.Exec(`INSERT INTO agent_conversation(id,created_at) VALUES(?,?)`, id, nowText())
	return err
}

func (s *SQL) HasConversation(id string) bool {
	var count int
	return s.db.QueryRow(`SELECT COUNT(1) FROM agent_conversation WHERE id=?`, id).Scan(&count) == nil && count == 1
}

func (s *SQL) SaveRun(run domain.RunRecord) error {
	return s.replace("agent_run", "id", run.ID, `INSERT INTO agent_run(id,conversation_id,payload,created_at) VALUES(?,?,?,?)`, run.ID, run.ConversationID, marshal(run), run.CreatedAt.Format(time.RFC3339Nano))
}

func (s *SQL) Runs(limit int) []domain.RunRecord {
	if limit <= 0 || limit > 100 {
		limit = 20
	}
	rows, err := s.db.Query(`SELECT payload FROM agent_run ORDER BY created_at DESC LIMIT ?`, limit)
	if err != nil {
		return nil
	}
	defer rows.Close()
	result := make([]domain.RunRecord, 0, limit)
	for rows.Next() {
		var payload string
		var run domain.RunRecord
		if rows.Scan(&payload) == nil && json.Unmarshal([]byte(payload), &run) == nil {
			result = append(result, run)
		}
	}
	return result
}

func (s *SQL) AddWatchlist(item domain.WatchlistItem) error {
	return s.replaceComposite("watchlist", "user_id=? AND fund_code=?", []any{item.UserID, item.FundCode}, `INSERT INTO watchlist(user_id,fund_code,created_at) VALUES(?,?,?)`, item.UserID, item.FundCode, item.CreatedAt.Format(time.RFC3339Nano))
}

func (s *SQL) RemoveWatchlist(userID, code string) error {
	_, err := s.db.Exec(`DELETE FROM watchlist WHERE user_id=? AND fund_code=?`, userID, code)
	return err
}

func (s *SQL) Watchlist(userID string) ([]domain.WatchlistItem, error) {
	rows, err := s.db.Query(`SELECT fund_code,created_at FROM watchlist WHERE user_id=? ORDER BY created_at`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]domain.WatchlistItem, 0)
	for rows.Next() {
		var code, created string
		if err := rows.Scan(&code, &created); err != nil {
			return nil, err
		}
		result = append(result, domain.WatchlistItem{UserID: userID, FundCode: code, CreatedAt: parseTime(created)})
	}
	return result, rows.Err()
}

func (s *SQL) SavePosition(position domain.Position) error {
	return s.replace("portfolio_position", "id", position.ID, `INSERT INTO portfolio_position(id,user_id,payload,updated_at) VALUES(?,?,?,?)`, position.ID, position.UserID, marshal(position), position.UpdatedAt.Format(time.RFC3339Nano))
}

func (s *SQL) DeletePosition(userID, id string) error {
	_, err := s.db.Exec(`DELETE FROM portfolio_position WHERE user_id=? AND id=?`, userID, id)
	return err
}

func (s *SQL) Positions(userID string) ([]domain.Position, error) {
	rows, err := s.db.Query(`SELECT payload FROM portfolio_position WHERE user_id=? ORDER BY updated_at`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]domain.Position, 0)
	for rows.Next() {
		var payload string
		var position domain.Position
		if rows.Scan(&payload) == nil && json.Unmarshal([]byte(payload), &position) == nil {
			result = append(result, position)
		}
	}
	return result, rows.Err()
}

func (s *SQL) SaveDocument(document domain.KnowledgeDocument, chunks []domain.KnowledgeChunk) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	_, _ = tx.Exec(`DELETE FROM knowledge_chunk WHERE document_id=?`, document.ID)
	_, _ = tx.Exec(`DELETE FROM knowledge_document WHERE id=?`, document.ID)
	if _, err := tx.Exec(`INSERT INTO knowledge_document(id,user_id,fund_code,payload,created_at) VALUES(?,?,?,?,?)`, document.ID, document.UserID, document.FundCode, marshal(document), document.CreatedAt.Format(time.RFC3339Nano)); err != nil {
		return err
	}
	for _, chunk := range chunks {
		if _, err := tx.Exec(`INSERT INTO knowledge_chunk(id,document_id,fund_code,payload,ordinal_no) VALUES(?,?,?,?,?)`, chunk.ID, chunk.DocumentID, chunk.FundCode, marshal(chunk), chunk.Ordinal); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (s *SQL) Documents(userID string) ([]domain.KnowledgeDocument, error) {
	rows, err := s.db.Query(`SELECT payload FROM knowledge_document WHERE user_id=? OR user_id='public' ORDER BY created_at DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]domain.KnowledgeDocument, 0)
	for rows.Next() {
		var payload string
		var document domain.KnowledgeDocument
		if rows.Scan(&payload) == nil && json.Unmarshal([]byte(payload), &document) == nil {
			result = append(result, document)
		}
	}
	return result, rows.Err()
}

func (s *SQL) Chunks(userID, fundCode string) ([]domain.KnowledgeChunk, error) {
	query := `SELECT c.payload FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id WHERE (d.user_id=? OR d.user_id='public')`
	args := []any{userID}
	if fundCode != "" {
		query += ` AND c.fund_code=?`
		args = append(args, fundCode)
	}
	rows, err := s.db.Query(query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]domain.KnowledgeChunk, 0)
	for rows.Next() {
		var payload string
		var chunk domain.KnowledgeChunk
		if rows.Scan(&payload) == nil && json.Unmarshal([]byte(payload), &chunk) == nil {
			result = append(result, chunk)
		}
	}
	return result, rows.Err()
}

func (s *SQL) SaveApproval(approval domain.Approval) error {
	return s.replace("agent_approval", "id", approval.ID, `INSERT INTO agent_approval(id,user_id,status,payload,created_at) VALUES(?,?,?,?,?)`, approval.ID, approval.UserID, approval.Status, marshal(approval), approval.CreatedAt.Format(time.RFC3339Nano))
}

func (s *SQL) Approval(userID, id string) (domain.Approval, error) {
	var payload string
	if err := s.db.QueryRow(`SELECT payload FROM agent_approval WHERE id=? AND user_id=?`, id, userID).Scan(&payload); err != nil {
		return domain.Approval{}, mapNotFound(err)
	}
	var value domain.Approval
	return value, json.Unmarshal([]byte(payload), &value)
}

func (s *SQL) Approvals(userID string) ([]domain.Approval, error) {
	rows, err := s.db.Query(`SELECT payload FROM agent_approval WHERE user_id=? ORDER BY created_at DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]domain.Approval, 0)
	for rows.Next() {
		var payload string
		var approval domain.Approval
		if rows.Scan(&payload) == nil && json.Unmarshal([]byte(payload), &approval) == nil {
			result = append(result, approval)
		}
	}
	return result, rows.Err()
}

func (s *SQL) replace(table, key, value, insert string, args ...any) error {
	return s.replaceComposite(table, key+"=?", []any{value}, insert, args...)
}

func (s *SQL) replaceComposite(table, where string, whereArgs []any, insert string, args ...any) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`DELETE FROM `+table+` WHERE `+where, whereArgs...); err != nil {
		return err
	}
	if _, err := tx.Exec(insert, args...); err != nil {
		return err
	}
	return tx.Commit()
}

func marshal(value any) string {
	bytes, _ := json.Marshal(value)
	return string(bytes)
}

func nowText() string { return time.Now().UTC().Format(time.RFC3339Nano) }

func parseTime(value string) time.Time {
	result, _ := time.Parse(time.RFC3339Nano, value)
	return result
}

func mapNotFound(err error) error {
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	return err
}

var _ Repository = (*SQL)(nil)
var _ Repository = (*Memory)(nil)

func sortChunks(chunks []domain.KnowledgeChunk) {
	sort.Slice(chunks, func(i, j int) bool { return chunks[i].Ordinal < chunks[j].Ordinal })
}
