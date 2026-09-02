package knowledge

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"path/filepath"
	"strings"
	"time"
)

func ParseUpload(ctx context.Context, filename, contentType string, data []byte, tikaURL string) (string, error) {
	extension := strings.ToLower(filepath.Ext(filename))
	switch extension {
	case ".txt", ".md", ".markdown", ".html", ".htm":
		return string(data), nil
	case ".pdf":
		if strings.TrimSpace(tikaURL) == "" {
			return "", errors.New("PDF parsing requires TIKA_URL; TXT, Markdown and HTML can be uploaded directly")
		}
		return parseWithTika(ctx, data, contentType, tikaURL)
	default:
		return "", fmt.Errorf("unsupported document type: %s", extension)
	}
}

func parseWithTika(ctx context.Context, data []byte, contentType, tikaURL string) (string, error) {
	parsedURL, err := url.Parse(strings.TrimRight(tikaURL, "/") + "/tika")
	if err != nil || (parsedURL.Scheme != "http" && parsedURL.Scheme != "https") {
		return "", errors.New("invalid TIKA_URL")
	}
	requestCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(requestCtx, http.MethodPut, parsedURL.String(), bytes.NewReader(data))
	if err != nil {
		return "", err
	}
	if contentType == "" {
		contentType = "application/pdf"
	}
	req.Header.Set("Content-Type", contentType)
	req.Header.Set("Accept", "text/plain")
	response, err := (&http.Client{Timeout: 30 * time.Second}).Do(req)
	if err != nil {
		return "", fmt.Errorf("Tika parser: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return "", fmt.Errorf("Tika parser returned HTTP %d", response.StatusCode)
	}
	result, err := io.ReadAll(io.LimitReader(response.Body, 2_000_001))
	if err != nil {
		return "", err
	}
	if len(result) > 2_000_000 {
		return "", errors.New("parsed document exceeds 2,000,000 characters")
	}
	return string(result), nil
}
