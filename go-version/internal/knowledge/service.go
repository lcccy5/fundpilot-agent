package knowledge

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"math"
	"regexp"
	"sort"
	"strings"
	"time"
	"unicode"

	"jijing-agent-go/internal/domain"
	"jijing-agent-go/internal/store"
)

type Service struct {
	repo store.Repository
}

func NewService(repo store.Repository) *Service { return &Service{repo: repo} }

func (s *Service) Ingest(userID, fundCode, title, sourceURL, content string) (domain.KnowledgeDocument, error) {
	content = normalize(content)
	if strings.TrimSpace(title) == "" || len([]rune(content)) < 20 {
		return domain.KnowledgeDocument{}, errors.New("title is required and content must contain at least 20 characters")
	}
	if len([]rune(content)) > 2_000_000 {
		return domain.KnowledgeDocument{}, errors.New("document exceeds 2,000,000 characters")
	}
	hashBytes := sha256.Sum256([]byte(content))
	hash := hex.EncodeToString(hashBytes[:])
	document := domain.KnowledgeDocument{ID: "doc_" + hash[:16], UserID: userID, FundCode: fundCode, Title: strings.TrimSpace(title), SourceURL: strings.TrimSpace(sourceURL), Content: content, Hash: hash, CreatedAt: time.Now().UTC()}
	texts := split(content, 800, 120)
	chunks := make([]domain.KnowledgeChunk, 0, len(texts))
	for i, text := range texts {
		chunks = append(chunks, domain.KnowledgeChunk{ID: document.ID + "_" + itoa(i), DocumentID: document.ID, FundCode: fundCode, Title: document.Title, SourceURL: sourceURL, Ordinal: i, Content: text})
	}
	return document, s.repo.SaveDocument(document, chunks)
}

func (s *Service) Documents(userID string) ([]domain.KnowledgeDocument, error) {
	return s.repo.Documents(userID)
}

func (s *Service) Search(userID, fundCode, query string, limit int) ([]domain.KnowledgeChunk, error) {
	query = strings.TrimSpace(query)
	if query == "" {
		return nil, errors.New("query is required")
	}
	if limit <= 0 || limit > 20 {
		limit = 5
	}
	chunks, err := s.repo.Chunks(userID, fundCode)
	if err != nil {
		return nil, err
	}
	queryTokens := tokens(query)
	queryVector := vector(queryTokens)
	result := make([]domain.KnowledgeChunk, 0)
	for _, chunk := range chunks {
		chunkTokens := tokens(chunk.Content)
		keyword := keywordScore(queryTokens, chunkTokens)
		semantic := cosine(queryVector, vector(chunkTokens))
		chunk.Score = .65*keyword + .35*semantic
		if chunk.Score > 0 {
			result = append(result, chunk)
		}
	}
	sort.SliceStable(result, func(i, j int) bool {
		if result[i].Score == result[j].Score {
			return result[i].Ordinal < result[j].Ordinal
		}
		return result[i].Score > result[j].Score
	})
	if len(result) > limit {
		result = result[:limit]
	}
	return result, nil
}

func normalize(value string) string {
	value = regexp.MustCompile(`(?s)<script.*?</script>`).ReplaceAllString(value, " ")
	value = regexp.MustCompile(`(?s)<style.*?</style>`).ReplaceAllString(value, " ")
	value = regexp.MustCompile(`<[^>]+>`).ReplaceAllString(value, " ")
	value = strings.ReplaceAll(value, "\r\n", "\n")
	value = regexp.MustCompile(`[ \t]+`).ReplaceAllString(value, " ")
	return strings.TrimSpace(value)
}

func split(value string, size, overlap int) []string {
	runes := []rune(value)
	if len(runes) <= size {
		return []string{value}
	}
	result := make([]string, 0, len(runes)/size+1)
	for start := 0; start < len(runes); {
		end := start + size
		if end > len(runes) {
			end = len(runes)
		}
		result = append(result, strings.TrimSpace(string(runes[start:end])))
		if end == len(runes) {
			break
		}
		start = end - overlap
	}
	return result
}

func tokens(value string) []string {
	value = strings.ToLower(value)
	words := regexp.MustCompile(`[a-z0-9]+`).FindAllString(value, -1)
	chinese := make([]rune, 0)
	for _, char := range []rune(value) {
		if unicode.Is(unicode.Han, char) {
			chinese = append(chinese, char)
		}
	}
	for i := range chinese {
		words = append(words, string(chinese[i]))
		if i+1 < len(chinese) {
			words = append(words, string(chinese[i:i+2]))
		}
	}
	return words
}

func keywordScore(query, document []string) float64 {
	if len(query) == 0 || len(document) == 0 {
		return 0
	}
	counts := map[string]int{}
	for _, token := range document {
		counts[token]++
	}
	score := 0.0
	for _, token := range query {
		if count := counts[token]; count > 0 {
			score += 1 + math.Log(float64(count))
		}
	}
	return score / float64(len(query))
}

func vector(values []string) [64]float64 {
	var result [64]float64
	for _, value := range values {
		hash := sha256.Sum256([]byte(value))
		index := int(hash[0]) % len(result)
		sign := 1.0
		if hash[1]&1 == 1 {
			sign = -1
		}
		result[index] += sign
	}
	return result
}

func cosine(left, right [64]float64) float64 {
	dot, leftNorm, rightNorm := 0.0, 0.0, 0.0
	for i := range left {
		dot += left[i] * right[i]
		leftNorm += left[i] * left[i]
		rightNorm += right[i] * right[i]
	}
	if leftNorm == 0 || rightNorm == 0 {
		return 0
	}
	return dot / math.Sqrt(leftNorm*rightNorm)
}

func itoa(value int) string {
	if value == 0 {
		return "0"
	}
	result := ""
	for value > 0 {
		result = string(rune('0'+value%10)) + result
		value /= 10
	}
	return result
}
