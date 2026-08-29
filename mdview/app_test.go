package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"unicode/utf8"

	"golang.org/x/text/encoding/simplifiedchinese"
	"golang.org/x/text/encoding/traditionalchinese"
)

const sampleMd = `C:\UserFile\WorkSpace\ZcodeWork\simplify2md\hybrid_auto\Frequency_Modulation_Nonlinearity_Correction_for_FMCW_SAL_Based_on_WVD_With_Gradient_Rotation_Enhancement.md`

// Relative image src must resolve against the md file's directory.
func TestResolveImagePathRelative(t *testing.T) {
	got := resolveImagePath(`images/figure-1.jpg`, sampleMd, "")
	want := filepath.Join(filepath.Dir(sampleMd), "images", "figure-1.jpg")
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
	got := resolveImagePath(`D:\pics\pic.png`, sampleMd, "")
	want := `D:\pics\pic.png`
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
}

// The real figure from the sample document must load non-empty bytes.
func TestLoadRealSampleImage(t *testing.T) {
	abs := resolveImagePath(`images/figure-1.jpg`, sampleMd, "")
	if _, err := os.Stat(abs); err != nil {
		t.Skipf("sample image not present: %v", err)
	}
	b, err := os.ReadFile(abs)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if len(b) == 0 {
		t.Fatal("empty bytes")
	}
	t.Logf("figure-1.jpg: %d bytes, mime=%s", len(b), mimeByExt(filepath.Ext(abs)))
}

// The real sample document is UTF-8 and must be detected as such.
func TestDetectEncodingUTF8(t *testing.T) {
	b, err := os.ReadFile(sampleMd)
	if err != nil {
		t.Skipf("sample missing: %v", err)
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
