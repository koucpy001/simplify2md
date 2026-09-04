package main

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"unicode/utf8"

	"golang.org/x/text/encoding/simplifiedchinese"
	"golang.org/x/text/encoding/traditionalchinese"
)

// makeSampleFile writes a self-contained UTF-8 Markdown fixture (Chinese text,
// an inline formula and an image reference) plus a real 1x1 PNG under
// images/, so image-loading and encoding-detection tests run without a
// developer-specific path.
func makeSampleFile(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	mdPath := filepath.Join(dir, "note.md")
	content := "# 测试文档\n\n这是一段中文内容，包含公式 $x^2+y^2=z^2$ 与图片 ![](images/figure-1.jpg)。\n\n"
	if err := os.WriteFile(mdPath, []byte(content), 0644); err != nil {
		t.Fatal(err)
	}
	imgDir := filepath.Join(dir, "images")
	if err := os.MkdirAll(imgDir, 0755); err != nil {
		t.Fatal(err)
	}
	img := image.NewRGBA(image.Rect(0, 0, 1, 1))
	img.Set(0, 0, color.RGBA{255, 0, 0, 255})
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(imgDir, "figure-1.jpg"), buf.Bytes(), 0644); err != nil {
		t.Fatal(err)
	}
	return mdPath
}

// Relative image src must resolve against the md file's directory.
func TestResolveImagePathRelative(t *testing.T) {
	md := filepath.Join("C:", "docs", "note.md")
	got := resolveImagePath(`images/figure-1.jpg`, md, "")
	want := filepath.Join(filepath.Dir(md), "images", "figure-1.jpg")
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
}

// Typora-style relative imageRoot in front matter.
func TestResolveImagePathWithImageRoot(t *testing.T) {
	got := resolveImagePath(`pic.png`, `C:\docs\note.md`, `./assets`)
	want := filepath.Join(`C:\docs`, `assets`, `pic.png`)
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
}

// Absolute src passes through untouched.
func TestResolveImagePathAbsolute(t *testing.T) {
	got := resolveImagePath(`D:\pics\pic.png`, "", "")
	want := `D:\pics\pic.png`
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
}

// The sample fixture's referenced image must load non-empty bytes with the
// correct mime.
func TestLoadRealSampleImage(t *testing.T) {
	mdPath := makeSampleFile(t)
	img, err := NewApp().LoadImageForSrc("images/figure-1.jpg", mdPath, "")
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if len(img.B64) == 0 {
		t.Fatal("empty bytes")
	}
	if img.Mime != "image/jpeg" {
		t.Fatalf("want image/jpeg, got %s", img.Mime)
	}
}

// The sample fixture document is UTF-8 and must be detected as such.
func TestDetectEncodingUTF8(t *testing.T) {
	b, err := os.ReadFile(makeSampleFile(t))
	if err != nil {
		t.Fatal(err)
	}
	if got := detectEncoding(b); got != "utf-8" {
		t.Fatalf("want utf-8, got %s", got)
	}
}

// GB18030-encoded simplified Chinese: detect, decode and re-encode losslessly.
func TestEncodingRoundtripGB18030(t *testing.T) {
	orig := strings.Repeat("# 测试文档\n\n这是一段简体中文内容，用于验证 GB18030 编码的检测、解码与回写是否无损。包含公式 $x^2+y^2=z^2$ 与图片 ![](images/figure-1.jpg)。\n\n", 8)
	enc, err := simplifiedchinese.GB18030.NewEncoder().Bytes([]byte(orig))
	if err != nil {
		t.Fatal(err)
	}
	if utf8.Valid(enc) {
		t.Skip("encoded bytes happened to be valid UTF-8")
	}
	if got := detectEncoding(enc); got != "gb18030" {
		t.Fatalf("want gb18030, got %s", got)
	}
	if dec := decodeBytes(enc, "gb18030"); dec != string(orig) {
		t.Fatal("gb18030 decode mismatch")
	}
	back, err := encodeContent(string(orig), "gb18030")
	if err != nil {
		t.Fatal(err)
	}
	if string(back) != string(enc) {
		t.Fatal("gb18030 re-encode mismatch")
	}
}

// Big5-encoded traditional Chinese: detect and decode losslessly.
func TestEncodingRoundtripBig5(t *testing.T) {
	orig := strings.Repeat("這是一段繁體中文測試文字，用來驗證 Big5 編碼的偵測、解碼與回寫是否正確無損。\n\n", 8)
	enc, err := traditionalchinese.Big5.NewEncoder().Bytes([]byte(orig))
	if err != nil {
		t.Fatal(err)
	}
	if utf8.Valid(enc) {
		t.Skip("encoded bytes happened to be valid UTF-8")
	}
	if got := detectEncoding(enc); got != "big5" {
		t.Fatalf("want big5, got %s", got)
	}
	if dec := decodeBytes(enc, "big5"); dec != string(orig) {
		t.Fatal("big5 decode mismatch")
	}
}

// CRLF files must be detected and re-expanded on save; LF passes through.
func TestDetectNewline(t *testing.T) {
	if got := detectNewline([]byte("a\r\nb\r\n")); got != "crlf" {
		t.Fatalf("want crlf, got %s", got)
	}
	if got := detectNewline([]byte("a\nb\n")); got != "lf" {
		t.Fatalf("want lf, got %s", got)
	}
}

func TestApplyNewline(t *testing.T) {
	got := applyNewline("a\r\nb\nc", "crlf")
	if got != "a\r\nb\r\nc" {
		t.Fatalf("crlf expansion mismatch: %q", got)
	}
	if got := applyNewline("a\nb\nc", "lf"); got != "a\nb\nc" {
		t.Fatalf("lf passthrough mismatch: %q", got)
	}
}

// An LF file with one stray CRLF keeps LF as its style (majority), instead of
// the whole file being rewritten to CRLF on save.
func TestDetectNewlineMixed(t *testing.T) {
	if got := detectNewline([]byte("a\nb\nc\r\nd\n")); got != "lf" {
		t.Fatalf("want lf for LF-dominant file, got %q", got)
	}
	if got := detectNewline([]byte("a\r\nb\r\nc\nd\r\n")); got != "crlf" {
		t.Fatalf("want crlf for CRLF-dominant file, got %q", got)
	}
}

// Binary content (NUL bytes / no CJK) must not be mis-detected as GB18030 by
// the sanity probe — it would get re-encoded on save and corrupted.
func TestDetectEncodingBinaryNotGB(t *testing.T) {
	bin := append([]byte{0x89, 'P', 'N', 'G', 0x00, 0x01, 0x02, 0xFE, 0xFF}, bytes.Repeat([]byte{0xAB, 0xCD, 0x00, 0x11}, 64)...)
	if got := detectEncoding(bin); got != "utf-8" {
		t.Fatalf("binary mis-detected as %q, want utf-8 fallback", got)
	}
}

// SaveFile writes atomically: content lands intact and no temp file remains.
func TestSaveFileAtomic(t *testing.T) {
	a := NewApp()
	path := filepath.Join(t.TempDir(), "note.md")
	a.allowWrite(path)
	if err := a.SaveFile(path, "# hi\n", "utf-8", "lf"); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(path)
	if err != nil || string(b) != "# hi\n" {
		t.Fatalf("content mismatch: %q err=%v", b, err)
	}
	entries, _ := os.ReadDir(filepath.Dir(path))
	for _, e := range entries {
		if strings.HasSuffix(e.Name(), ".tmp") {
			t.Fatalf("temp file left behind: %s", e.Name())
		}
	}
	// Overwrite must also work (rename replaces the existing file).
	if err := a.SaveFile(path, "# bye\n", "utf-8", "lf"); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(path); string(b) != "# bye\n" {
		t.Fatalf("overwrite mismatch: %q", b)
	}
}

// firstExistingFile picks the first argument naming a real file and skips
// flags, so `simplify2md.exe C:\doc.md` opens the right document.
func TestFirstExistingFile(t *testing.T) {
	f := filepath.Join(t.TempDir(), "x.md")
	if err := os.WriteFile(f, []byte("x"), 0644); err != nil {
		t.Fatal(err)
	}
	got, ok := firstExistingFile([]string{"-flag", f})
	if !ok || got != f {
		t.Fatalf("got %q %v, want %q", got, ok, f)
	}
	if _, ok := firstExistingFile([]string{"-flag", filepath.Join(t.TempDir(), "gone.md")}); ok {
		t.Fatal("missing file must not match")
	}
}

// Oversized images are rejected up front instead of being base64-bridged.
func TestLoadImageTooLarge(t *testing.T) {
	big := filepath.Join(t.TempDir(), "big.png")
	if err := os.WriteFile(big, make([]byte, maxImageBytes+1), 0644); err != nil {
		t.Fatal(err)
	}
	a := NewApp()
	if _, err := a.LoadImageForSrc(big, "", ""); err == nil || !strings.Contains(err.Error(), "larger than") {
		t.Fatalf("want size-cap error, got %v", err)
	}
}
