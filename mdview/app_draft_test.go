package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// newDraftApp returns an App whose autosave dir is an isolated temp dir and
// whose config dir is isolated too, so draft + recent tests never touch the
// real user filesystem.
func newDraftApp(t *testing.T) *App {
	t.Helper()
	a := NewApp()
	a.draftDir = t.TempDir()
	a.configDir = t.TempDir()
	return a
}

// Round-trip a draft through save → list → load → clear.
func TestDraftRoundTrip(t *testing.T) {
	a := newDraftApp(t)
	const content = "# draft\n\nhello world\n"
	if err := a.SaveDraft(valid40Hex, content); err != nil {
		t.Fatalf("save: %v", err)
	}
	list, err := a.ListDrafts()
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(list) != 1 || list[0].Key != valid40Hex {
		t.Fatalf("list mismatch: %+v", list)
	}
	got, err := a.LoadDraft(valid40Hex)
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if got != content {
		t.Fatalf("content mismatch: got %q want %q", got, content)
	}
	if err := a.ClearDraft(valid40Hex); err != nil {
		t.Fatalf("clear: %v", err)
	}
	if _, err := a.LoadDraft(valid40Hex); err == nil {
		t.Fatal("load after clear should error")
	}
}

// Untitled is an accepted key; a 40-hex key is accepted.
func TestDraftAcceptedKeys(t *testing.T) {
	a := newDraftApp(t)
	if err := a.SaveDraft("untitled", "x"); err != nil {
		t.Fatalf("untitled rejected: %v", err)
	}
	if err := a.SaveDraft(valid40Hex, "y"); err != nil {
		t.Fatalf("40-hex rejected: %v", err)
	}
}

// Traversal / malformed keys must be rejected and must NOT write outside the
// autosave dir.
func TestDraftRejectedKeys(t *testing.T) {
	a := newDraftApp(t)
	dir := a.autosaveDir()
	bad := []string{
		"../../../etc/passwd",
		"..%2F",            // encoded separator, still not 40-hex/untitled
		"..\\..\\win.ini",  // windows separator
		"short",
		"ABCDEF0123456789ABCDEF0123456789ABCDEF0Z", // 41 chars, non-hex tail
		"",
	}
	for _, k := range bad {
		if err := a.SaveDraft(k, "evil"); err == nil {
			t.Fatalf("key %q should be rejected", k)
		}
	}
	// The autosave dir must not contain any file written by the rejected keys.
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		if strings.ContainsAny(e.Name(), "/\\") || strings.Contains(e.Name(), "..") {
			t.Fatalf("escape write detected: %s", e.Name())
		}
	}
}

// Listing an empty (or non-existent) autosave dir returns an empty slice.
func TestDraftListEmpty(t *testing.T) {
	a := newDraftApp(t)
	list, err := a.ListDrafts()
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(list) != 0 {
		t.Fatalf("expected empty list, got %+v", list)
	}
}

// Drafts are sorted by modification time, newest first.
func TestDraftListSortedByModTime(t *testing.T) {
	a := newDraftApp(t)
	k1 := "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	k2 := "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
	k3 := "cccccccccccccccccccccccccccccccccccccccc"
	if err := a.SaveDraft(k1, "1"); err != nil {
		t.Fatal(err)
	}
	if err := a.SaveDraft(k2, "2"); err != nil {
		t.Fatal(err)
	}
	if err := a.SaveDraft(k3, "3"); err != nil {
		t.Fatal(err)
	}
	// Backdate k1 and k2 so the creation order no longer matches modtime order.
	base := time.Now()
	if err := os.Chtimes(filepath.Join(a.autosaveDir(), k1+".md"), base.Add(-3*time.Hour), base.Add(-3*time.Hour)); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(filepath.Join(a.autosaveDir(), k2+".md"), base.Add(-1*time.Hour), base.Add(-1*time.Hour)); err != nil {
		t.Fatal(err)
	}
	// k3 keeps "now".
	list, err := a.ListDrafts()
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(list) != 3 {
		t.Fatalf("expected 3 drafts, got %d", len(list))
	}
	want := []string{k3, k2, k1}
	for i, w := range want {
		if list[i].Key != w {
			t.Fatalf("order[%d] = %q, want %q (full: %+v)", i, list[i].Key, w, list)
		}
	}
}

// ClearRecents wipes recents and LastFile in the config file.
func TestClearRecents(t *testing.T) {
	a := newDraftApp(t)
	a.recordRecent("/tmp/one.md")
	a.recordRecent("/tmp/two.md")
	a.recordRecent("/tmp/three.md")
	if got := a.GetRecents(); len(got) != 3 {
		t.Fatalf("precondition: expected 3 recents, got %d", len(got))
	}
	a.ClearRecents()
	if got := a.GetRecents(); len(got) != 0 {
		t.Fatalf("recents not cleared: %+v", got)
	}
	// Config file's LastFile must be empty after ClearRecents.
	b, err := os.ReadFile(a.configPath())
	if err != nil {
		t.Fatalf("read config: %v", err)
	}
	if strings.Contains(string(b), "/tmp/three.md") {
		t.Fatalf("LastFile not cleared: %s", b)
	}
}
