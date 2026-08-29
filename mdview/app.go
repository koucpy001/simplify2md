package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"time"
	"unicode/utf8"

	"github.com/fsnotify/fsnotify"
	"github.com/saintfish/chardet"
	"github.com/wailsapp/wails/v2/pkg/runtime"
	"golang.org/x/text/encoding/simplifiedchinese"
	"golang.org/x/text/encoding/traditionalchinese"
)

// App struct
type App struct {
	ctx context.Context
	// dirty/forceQuit are written from JS-binding goroutines and read on the
	// window-close path — atomics keep that cross-thread handoff race-free.
	dirty          atomic.Bool
	forceQuit      atomic.Bool
	watcher        *fsnotify.Watcher
	watchDone      chan struct{}
	watchPath      string
	lastSelfWriteNs int64
}

func NewApp() *App { return &App{} }

func (a *App) startup(ctx context.Context) { a.ctx = ctx }

// SetDirty records the modified flag; beforeClose uses it to guard exit.
func (a *App) SetDirty(d bool) { a.dirty.Store(d) }

// beforeClose blocks window close while there are unsaved changes and asks
// the frontend to show its own confirm dialog; the frontend then calls
// ConfirmExit to quit. A native MessageDialog here is not portable: close
// must hinge on matching its button-label result, which varies by platform.
func (a *App) beforeClose(ctx context.Context) (prevent bool) {
	if a.forceQuit.Load() || !a.dirty.Load() {
		return false
	}
	runtime.EventsEmit(a.ctx, "mdview:confirm-exit")
	return true
}

// ConfirmExit quits unconditionally; the frontend calls it after the user
// agrees to discard unsaved changes.
func (a *App) ConfirmExit() {
	a.forceQuit.Store(true)
	runtime.Quit(a.ctx)
}

// SetTitle updates the native window title.
func (a *App) SetTitle(title string) { runtime.WindowSetTitle(a.ctx, title) }

// OpenResult is returned by OpenFile / ReadFileAt.
type OpenResult struct {
	Path     string `json:"path"`
	Content  string `json:"content"`
	Encoding string `json:"encoding"`
	Newline  string `json:"newline"`
}

// OpenFile shows a native file dialog and reads the chosen Markdown file.
func (a *App) OpenFile() (OpenResult, error) {
	path, err := runtime.OpenFileDialog(a.ctx, runtime.OpenDialogOptions{
		Title: "打开 Markdown",
		Filters: []runtime.FileFilter{
			{DisplayName: "Markdown", Pattern: "*.md;*.markdown;*.txt"},
			{DisplayName: "所有文件", Pattern: "*.*"},
		},
	})
	if err != nil {
		return OpenResult{}, err
	}
	if path == "" {
		return OpenResult{}, errors.New("cancelled")
	}
	return a.ReadFileAt(path)
}

// ReadFileAt reads a given path with encoding + newline detection, starts
// watching it for external changes, and records it as the most recent file.
func (a *App) ReadFileAt(path string) (OpenResult, error) {
	if path == "" {
		return OpenResult{}, errors.New("empty path")
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return OpenResult{}, err
	}
	enc := detectEncoding(b)
	nl := detectNewline(b)
	a.recordRecent(path)
	a.startWatching(path)
	return OpenResult{Path: path, Content: decodeBytes(b, enc), Encoding: enc, Newline: nl}, nil
}

// SaveFile writes content back to path in the file's original encoding and
// newline style, so GBK/Big5/CRLF files round-trip without corruption.
func (a *App) SaveFile(path, content, encoding, newline string) error {
	if path == "" {
		return errors.New("no path")
	}
	// Own writes are ignored by the file watcher (self-write window).
	atomic.StoreInt64(&a.lastSelfWriteNs, time.Now().Add(500*time.Millisecond).UnixNano())
	b, err := encodeContent(applyNewline(content, newline), newline)
	if err != nil {
		return err
	}
	return os.WriteFile(path, b, 0644)
}

// ---- external file change detection ----

// startWatching watches the file's directory and notifies the frontend when
// the file itself is modified by something other than this app. Watching the
// directory (not the file) survives editors that replace files on save.
func (a *App) startWatching(path string) {
	a.stopWatching()
	w, err := fsnotify.NewWatcher()
	if err != nil {
		return
	}
	if err := w.Add(filepath.Dir(path)); err != nil {
		w.Close()
		return
	}
	cleanPath := filepath.Clean(path)
	done := make(chan struct{})
	a.watcher = w
	a.watchDone = done
	go func() {
		defer w.Close()
		for {
			select {
			case <-done:
				return
			case ev, ok := <-w.Events:
				if !ok {
					return
				}
				if filepath.Clean(ev.Name) != cleanPath {
					continue
				}
				if !ev.Has(fsnotify.Write) && !ev.Has(fsnotify.Create) {
					continue
				}
				if time.Now().UnixNano() < atomic.LoadInt64(&a.lastSelfWriteNs) {
					continue
				}
				runtime.EventsEmit(a.ctx, "mdview:file-changed")
			case _, ok := <-w.Errors:
				if !ok {
					return
				}
			}
		}
	}()
}

func (a *App) stopWatching() {
	if a.watchDone != nil {
		close(a.watchDone)
		a.watchDone = nil
	}
	if a.watcher != nil {
		a.watcher.Close()
		a.watcher = nil
	}
}

// ---- image loading ----

// ImageData carries base64 bytes + mime for a local image.
type ImageData struct {
	B64  string `json:"b64"`
	Mime string `json:"mime"`
}

// LoadImageForSrc resolves a (possibly relative) image src against the open
// Markdown file's directory and optional imageRoot, reads the bytes, and
// returns base64 + mime so the WebView can use a data URL. This mirrors
// soloMD's image-resolve.ts logic in Go, replacing Tauri's convertFileSrc.
func (a *App) LoadImageForSrc(src, mdPath, imageRoot string) (ImageData, error) {
	if src == "" {
		return ImageData{}, errors.New("empty src")
	}
	abs := resolveImagePath(src, mdPath, imageRoot)
	b, err := os.ReadFile(abs)
	if err != nil {
		return ImageData{}, err
	}
	return ImageData{B64: base64.StdEncoding.EncodeToString(b), Mime: mimeByExt(filepath.Ext(abs))}, nil
}

// resolveImagePath turns a markdown image src into an absolute filesystem
// path: absolute srcs pass through; relative srcs resolve against imageRoot
// (if absolute) or the md file's directory, like Typora/soloMD.
func resolveImagePath(src, mdPath, imageRoot string) string {
	src = strings.ReplaceAll(src, "\\", "/")
	abs := strings.HasPrefix(src, "/") || hasDriveLetter(src)
	if !abs {
		base := ""
		if imageRoot != "" {
			if strings.HasPrefix(imageRoot, "/") || hasDriveLetter(imageRoot) {
				base = imageRoot
			} else if mdPath != "" {
				base = filepath.Join(filepath.Dir(mdPath), imageRoot)
			}
		}
		if base == "" && mdPath != "" {
			base = filepath.Dir(mdPath)
		}
		src = filepath.Join(base, src)
	}
	return filepath.Clean(strings.ReplaceAll(src, "/", string(filepath.Separator)))
}

func hasDriveLetter(p string) bool {
	return len(p) >= 2 && p[1] == ':'
}

func mimeByExt(ext string) string {
	switch strings.ToLower(ext) {
	case ".png":
		return "image/png"
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".gif":
		return "image/gif"
	case ".webp":
		return "image/webp"
	case ".svg":
		return "image/svg+xml"
	case ".bmp":
		return "image/bmp"
	case ".tif", ".tiff":
		return "image/tiff"
	default:
		return "application/octet-stream"
	}
}

// ---- encoding detection / conversion ----

// detectEncoding: valid UTF-8 wins; otherwise let chardet pick between
// GB18030 (covers GBK/GB2312) and Big5. When chardet can't identify a CJK
// charset, fall back to a GB18030 sanity probe: a clean decode (no
// replacement chars) is accepted, since nearly all non-UTF-8 Chinese text
// found in the wild is GBK-family. Final fallback is UTF-8.
func detectEncoding(b []byte) string {
	if utf8.Valid(b) {
		return "utf-8"
	}
	if res, err := chardet.NewTextDetector().DetectBest(b); err == nil {
		switch res.Charset {
		case "GB-2312", "GB18030", "GBK":
			return "gb18030"
		case "Big5":
			return "big5"
		}
	}
	if dec, err := simplifiedchinese.GB18030.NewDecoder().Bytes(b); err == nil &&
		!strings.Contains(string(dec), "\uFFFD") {
		return "gb18030"
	}
	return "utf-8"
}

func decodeBytes(b []byte, enc string) string {
	switch enc {
	case "gb18030":
		if dec, err := simplifiedchinese.GB18030.NewDecoder().Bytes(b); err == nil {
			return string(dec)
		}
	case "big5":
		if dec, err := traditionalchinese.Big5.NewDecoder().Bytes(b); err == nil {
			return string(dec)
		}
	}
	return string(b)
}

func encodeContent(s, enc string) ([]byte, error) {
	switch enc {
	case "gb18030":
		return simplifiedchinese.GB18030.NewEncoder().Bytes([]byte(s))
	case "big5":
		return traditionalchinese.Big5.NewEncoder().Bytes([]byte(s))
	default:
		return []byte(s), nil
	}
}

// ---- newline detection / conversion ----

func detectNewline(b []byte) string {
	if bytes.Contains(b, []byte("\r\n")) {
		return "crlf"
	}
	return "lf"
}

// applyNewline converts content to the file's original newline style. The
// editor (CodeMirror) keeps everything as LF internally, so a CRLF file is
// re-expanded on save; an LF file passes through untouched.
func applyNewline(content, newline string) string {
	if newline == "crlf" {
		s := strings.ReplaceAll(content, "\r\n", "\n")
		return strings.ReplaceAll(s, "\n", "\r\n")
	}
	return content
}

// ---- recent files / session restore ----

type appConfig struct {
	RecentFiles []string `json:"recentFiles"`
	LastFile    string   `json:"lastFile"`
}

func (a *App) configPath() string {
	dir, err := os.UserConfigDir()
	if err != nil {
		return ""
	}
	return filepath.Join(dir, "simplify2md", "config.json")
}

func (a *App) loadConfig() appConfig {
	var c appConfig
	if p := a.configPath(); p != "" {
		if b, err := os.ReadFile(p); err == nil {
			json.Unmarshal(b, &c)
		}
	}
	return c
}

func (a *App) saveConfig(c appConfig) {
	p := a.configPath()
	if p == "" {
		return
	}
	if err := os.MkdirAll(filepath.Dir(p), 0755); err != nil {
		return
	}
	if b, err := json.MarshalIndent(c, "", "  "); err == nil {
		os.WriteFile(p, b, 0644)
	}
}

func (a *App) recordRecent(path string) {
	c := a.loadConfig()
	out := []string{path}
	for _, p := range c.RecentFiles {
		if p != path {
			out = append(out, p)
		}
		if len(out) >= 10 {
			break
		}
	}
	c.RecentFiles = out
	c.LastFile = path
	a.saveConfig(c)
}

// GetRecents returns the recent-file list, most recent first.
func (a *App) GetRecents() []string { return a.loadConfig().RecentFiles }

// RemoveRecent drops a path from the recent list, e.g. when the file has
// disappeared from disk and startup restore can no longer open it.
func (a *App) RemoveRecent(path string) {
	c := a.loadConfig()
	out := make([]string, 0, len(c.RecentFiles))
	for _, p := range c.RecentFiles {
		if p != path {
			out = append(out, p)
		}
	}
	c.RecentFiles = out
	a.saveConfig(c)
}
