package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode/utf8"

	"github.com/fsnotify/fsnotify"
	"github.com/saintfish/chardet"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/runtime"
	"golang.org/x/text/encoding/simplifiedchinese"
	"golang.org/x/text/encoding/traditionalchinese"
)

// App struct
type App struct {
	ctx context.Context
	// dirty/forceQuit are written from JS-binding goroutines and read on the
	// window-close path — atomics keep that cross-thread handoff race-free.
	dirty     atomic.Bool
	forceQuit atomic.Bool
	// watcher/watchDone are written from Wails binding goroutines
	// (startWatching/stopWatching) and must be guarded by watchMu to avoid a
	// concurrent read/write or a double close.
	watcher   *fsnotify.Watcher
	watchDone chan struct{}
	watchMu   sync.Mutex
	// lastSelfWriteNs marks a self-write window (Unix nanos) so the file
	// watcher ignores our own saves. atomic.Int64 keeps access race-free.
	lastSelfWriteNs atomic.Int64
	// allowedWrites is the set of paths the user has explicitly opened or
	// chosen via a save dialog. SaveFile only writes to these, so a malicious
	// or buggy frontend payload cannot redirect a save to an arbitrary path.
	allowedWrites map[string]struct{}
	writeMu       sync.Mutex
	// startupFile holds the path passed on the command line (e.g. by the .md
	// file association). The frontend reads it once after mount.
	startupFile string
}

func NewApp() *App { return &App{} }

func (a *App) startup(ctx context.Context) { a.ctx = ctx }

// SetStartupArgs captures the process arguments so the frontend can open the
// file the OS launched with (double-click, "Open with").
func (a *App) SetStartupArgs(args []string) {
	if p, ok := firstExistingFile(args); ok {
		a.startupFile = p
	}
}

// GetStartupFile returns the file path passed on the command line, or "" when
// the app was launched without one.
func (a *App) GetStartupFile() string { return a.startupFile }

// onSecondInstanceLaunch routes a second launch's argument (another
// double-clicked file) into the running window instead of opening a second
// one; main.go enables the single-instance lock this hook belongs to.
func (a *App) onSecondInstanceLaunch(data options.SecondInstanceData) {
	if a.ctx == nil {
		return
	}
	if p, ok := firstExistingFile(data.Args); ok {
		runtime.EventsEmit(a.ctx, "mdview:open-path", p)
	}
}

// firstExistingFile returns the first argument that names an existing file,
// filtering out flags and vanished paths.
func firstExistingFile(args []string) (string, bool) {
	for _, arg := range args {
		if strings.HasPrefix(arg, "-") {
			continue
		}
		if st, err := os.Stat(arg); err == nil && !st.IsDir() {
			return arg, true
		}
	}
	return "", false
}

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
	a.allowWrite(path)
	a.startWatching(path)
	return OpenResult{Path: path, Content: decodeBytes(b, enc), Encoding: enc, Newline: nl}, nil
}

// allowWrite records path as a permitted save target (after an explicit
// open or save-dialog choice). filepath.Clean normalizes case/separator
// differences between the opened and the later saved path.
func (a *App) allowWrite(path string) {
	a.writeMu.Lock()
	if a.allowedWrites == nil {
		a.allowedWrites = make(map[string]struct{})
	}
	a.allowedWrites[filepath.Clean(path)] = struct{}{}
	a.writeMu.Unlock()
}

// canWrite reports whether path is a permitted save target.
func (a *App) canWrite(path string) bool {
	a.writeMu.Lock()
	defer a.writeMu.Unlock()
	_, ok := a.allowedWrites[filepath.Clean(path)]
	return ok
}

// SaveFile writes content back to path in the file's original encoding and
// newline style, so GBK/Big5/CRLF files round-trip without corruption. The
// write is atomic (temp file + rename) and records the path as most recent.
func (a *App) SaveFile(path, content, encoding, newline string) error {
	if path == "" {
		return errors.New("no path")
	}
	if !a.canWrite(path) {
		return errors.New("path not permitted")
	}
	// Own writes are ignored by the file watcher (self-write window).
	a.lastSelfWriteNs.Store(time.Now().Add(500 * time.Millisecond).UnixNano())
	b, err := encodeContent(applyNewline(content, newline), encoding)
	if err != nil {
		return err
	}
	if err := writeFileAtomic(path, b); err != nil {
		return err
	}
	a.recordRecent(path)
	a.allowWrite(path)
	a.startWatching(path)
	return nil
}

// PickSavePath shows a native save dialog and returns the chosen path ("" when
// cancelled); the frontend then passes it to SaveFile. This backs 另存为 and
// the first save of an untitled document.
func (a *App) PickSavePath(defaultName string) (string, error) {
	p, err := runtime.SaveFileDialog(a.ctx, runtime.SaveDialogOptions{
		Title:           "保存 Markdown",
		DefaultFilename: defaultName,
		Filters: []runtime.FileFilter{
			{DisplayName: "Markdown", Pattern: "*.md;*.markdown;*.txt"},
		},
	})
	if err != nil {
		return "", err
	}
	if p != "" {
		a.allowWrite(p)
	}
	return p, nil
}

// writeFileAtomic writes b to path via a temp file in the same directory plus
// a rename, so a crash mid-write can never leave a truncated file behind.
func writeFileAtomic(path string, b []byte) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), filepath.Base(path)+".*.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	if _, err := tmp.Write(b); err != nil {
		tmp.Close()
		os.Remove(tmpName)
		return err
	}
	// Flush to stable storage before rename so a power loss after rename
	// cannot leave a truncated/empty file.
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		os.Remove(tmpName)
		return err
	}
	if err := tmp.Close(); err != nil {
		os.Remove(tmpName)
		return err
	}
	if err := os.Rename(tmpName, path); err != nil {
		os.Remove(tmpName)
		return err
	}
	return nil
}

// ---- external file change detection ----

// startWatching watches the file's directory and notifies the frontend when
// the file itself is modified by something other than this app. Watching the
// directory (not the file) survives editors that replace files on save.
func (a *App) startWatching(path string) {
	a.watchMu.Lock()
	a.stopWatchingLocked()
	w, err := fsnotify.NewWatcher()
	if err != nil {
		a.watchMu.Unlock()
		return
	}
	if err := w.Add(filepath.Dir(path)); err != nil {
		w.Close()
		a.watchMu.Unlock()
		return
	}
	cleanPath := filepath.Clean(path)
	done := make(chan struct{})
	a.watcher = w
	a.watchDone = done
	a.watchMu.Unlock()
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
				if time.Now().UnixNano() < a.lastSelfWriteNs.Load() {
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
	a.watchMu.Lock()
	a.stopWatchingLocked()
	a.watchMu.Unlock()
}

// stopWatchingLocked tears down the current watcher. Caller must hold watchMu.
func (a *App) stopWatchingLocked() {
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

// maxImageBytes caps what LoadImageForSrc will bridge to the WebView; base64
// costs ×1.33 memory, and nothing in a Markdown doc needs a bigger payload.
const maxImageBytes = 32 << 20

// LoadImageForSrc resolves a (possibly relative) image src against the open
// Markdown file's directory and optional imageRoot, reads the bytes, and
// returns base64 + mime so the WebView can use a data URL. This mirrors
// soloMD's image-resolve.ts logic in Go, replacing Tauri's convertFileSrc.
func (a *App) LoadImageForSrc(src, mdPath, imageRoot string) (ImageData, error) {
	if src == "" {
		return ImageData{}, errors.New("empty src")
	}
	abs := resolveImagePath(src, mdPath, imageRoot)
	// Reject unsupported image extensions up front (before touching disk):
	// mimeByExt returns application/octet-stream for anything it doesn't
	// recognize, which would otherwise be bridged to the WebView as a data URL.
	if mimeByExt(filepath.Ext(abs)) == "application/octet-stream" {
		return ImageData{}, errors.New("unsupported image type")
	}
	st, err := os.Stat(abs)
	if err != nil {
		return ImageData{}, err
	}
	if st.Size() > maxImageBytes {
		return ImageData{}, fmt.Errorf("image larger than %d MB: %s", maxImageBytes>>20, abs)
	}
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
// charset, fall back to a GB18030 sanity probe: GB18030 decodes nearly every
// byte sequence cleanly, so the probe is only trusted for text-like input —
// no NUL bytes, and the decoded string must actually contain CJK. Random
// binary and Western text fall back to UTF-8 instead of being mis-detected
// (and later re-encoded) as GB18030.
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
	if bytes.IndexByte(b, 0) == -1 {
		if dec, err := simplifiedchinese.GB18030.NewDecoder().Bytes(b); err == nil &&
			!strings.ContainsRune(string(dec), '\uFFFD') && containsCJK(string(dec)) {
			return "gb18030"
		}
	}
	// Symmetric Big5 fallback: when chardet misses a Big5 file it would
	// otherwise be decoded as UTF-8 and permanently corrupted on save.
	if bytes.IndexByte(b, 0) == -1 {
		if dec, err := traditionalchinese.Big5.NewDecoder().Bytes(b); err == nil &&
			!strings.ContainsRune(string(dec), '\uFFFD') && containsCJK(string(dec)) {
			return "big5"
		}
	}
	return "utf-8"
}

// containsCJK reports whether s has at least one Han character.
func containsCJK(s string) bool {
	for _, r := range s {
		if (r >= 0x3400 && r <= 0x9FFF) || (r >= 0xF900 && r <= 0xFAFF) {
			return true
		}
	}
	return false
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

// detectNewline picks the dominant line-ending style instead of "any CRLF
// wins", so an LF file with one stray CRLF keeps LF (only that one line is
// normalized on save) rather than having the whole file rewritten to CRLF.
func detectNewline(b []byte) string {
	crlf := bytes.Count(b, []byte("\r\n"))
	lf := bytes.Count(b, []byte("\n")) - crlf
	if crlf > lf {
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
