package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// valid40Hex is a deterministic 40-hex sha1-shaped key used by the CRUD tests.
const valid40Hex = "da39a3ee5e6b4b0d3255bfef95601890afd80709"

// setAppVersion swaps the package-level appVersion for a test and returns a
// restore func. Sequential (non-parallel) tests only.
func setAppVersion(v string) func() {
	old := appVersion
	appVersion = v
	return func() { appVersion = old }
}

// --- versionGreaterThan comparator unit tests ---

func TestVersionGreaterThanSame(t *testing.T) {
	has, err := versionGreaterThan("1.2.3", "1.2.3")
	if err != nil || has {
		t.Fatalf("same version: got has=%v err=%v", has, err)
	}
}

func TestVersionGreaterThanNewer(t *testing.T) {
	has, err := versionGreaterThan("1.3.0", "1.2.3")
	if err != nil || !has {
		t.Fatalf("newer available: got has=%v err=%v", has, err)
	}
}

func TestVersionGreaterThanOlder(t *testing.T) {
	has, err := versionGreaterThan("1.0.0", "1.2.3")
	if err != nil || has {
		t.Fatalf("older latest: got has=%v err=%v", has, err)
	}
}

func TestVersionGreaterThanVPrefixStripped(t *testing.T) {
	has, err := versionGreaterThan("v1.2.3", "1.2.2")
	if err != nil || !has {
		t.Fatalf("v-prefix strip: got has=%v err=%v", has, err)
	}
}

func TestVersionGreaterThanMalformed(t *testing.T) {
	has, err := versionGreaterThan("not-a-version", "1.2.3")
	if err == nil {
		t.Fatal("malformed latest must error")
	}
	if has {
		t.Fatal("malformed version must not report an update")
	}
}

// --- CheckForUpdate HTTP tests ---

func TestCheckForUpdateDevNoCall(t *testing.T) {
	restore := setAppVersion("dev")
	defer restore()
	// Server fails the test if it is ever hit: dev must short-circuit.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Error("dev build must not call the update endpoint")
	}))
	defer srv.Close()
	a := NewApp()
	a.updateClient = srv.Client()
	a.updateBaseURL = srv.URL
	info, err := a.CheckForUpdate()
	if err != nil {
		t.Fatalf("dev: unexpected error %v", err)
	}
	if info.HasUpdate {
		t.Fatal("dev: HasUpdate must be false")
	}
}

func TestCheckForUpdate200WithUpdate(t *testing.T) {
	restore := setAppVersion("1.0.0")
	defer restore()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("User-Agent") != "simplify2md" {
			t.Errorf("missing User-Agent, got %q", r.Header.Get("User-Agent"))
		}
		if r.Header.Get("Accept") != "application/vnd.github+json" {
			t.Errorf("missing Accept header, got %q", r.Header.Get("Accept"))
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"tag_name":"v1.2.3","html_url":"https://github.com/koucpy001/simplify2md/releases/tag/v1.2.3"}`))
	}))
	defer srv.Close()
	a := NewApp()
	a.updateClient = srv.Client()
	a.updateBaseURL = srv.URL
	info, err := a.CheckForUpdate()
	if err != nil {
		t.Fatalf("error: %v", err)
	}
	if !info.HasUpdate {
		t.Fatal("expected HasUpdate=true")
	}
	if info.LatestTag != "v1.2.3" {
		t.Fatalf("latest tag = %q", info.LatestTag)
	}
	if info.HtmlURL != "https://github.com/koucpy001/simplify2md/releases/tag/v1.2.3" {
		t.Fatalf("html url whitelisted = %q", info.HtmlURL)
	}
}

func TestCheckForUpdate200NoUpdate(t *testing.T) {
	restore := setAppVersion("1.2.3")
	defer restore()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"tag_name":"1.2.3","html_url":"https://github.com/koucpy001/simplify2md/releases/tag/1.2.3"}`))
	}))
	defer srv.Close()
	a := NewApp()
	a.updateClient = srv.Client()
	a.updateBaseURL = srv.URL
	info, err := a.CheckForUpdate()
	if err != nil {
		t.Fatalf("error: %v", err)
	}
	if info.HasUpdate {
		t.Fatal("expected HasUpdate=false (up to date)")
	}
}

func TestCheckForUpdate403(t *testing.T) {
	restore := setAppVersion("1.0.0")
	defer restore()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
	}))
	defer srv.Close()
	a := NewApp()
	a.updateClient = srv.Client()
	a.updateBaseURL = srv.URL
	_, err := a.CheckForUpdate()
	if err == nil {
		t.Fatal("403 must return an error (no panic)")
	}
}

func TestCheckForUpdateTimeout(t *testing.T) {
	restore := setAppVersion("1.0.0")
	defer restore()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(200 * time.Millisecond)
	}))
	defer srv.Close()
	a := NewApp()
	a.updateClient = &http.Client{Timeout: 20 * time.Millisecond}
	a.updateBaseURL = srv.URL
	_, err := a.CheckForUpdate()
	if err == nil {
		t.Fatal("timeout must return an error (no panic)")
	}
}

func TestCheckForUpdateMalformedJSON(t *testing.T) {
	restore := setAppVersion("1.0.0")
	defer restore()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("this is not json"))
	}))
	defer srv.Close()
	a := NewApp()
	a.updateClient = srv.Client()
	a.updateBaseURL = srv.URL
	_, err := a.CheckForUpdate()
	if err == nil {
		t.Fatal("malformed JSON must return an error (no panic)")
	}
}

func TestCheckForUpdateHtmlURLNotWhitelisted(t *testing.T) {
	restore := setAppVersion("1.0.0")
	defer restore()
	cases := []string{
		"http://github.com/koucpy001/simplify2md/releases/x", // wrong scheme
		"javascript:alert(1)",                                // protocol handler
		"https://evil.example.com/simplify2md/releases/x",     // foreign domain
	}
	for _, bad := range cases {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			_, _ = w.Write([]byte(`{"tag_name":"9.9.9","html_url":"` + bad + `"}`))
		}))
		a := NewApp()
		a.updateClient = srv.Client()
		a.updateBaseURL = srv.URL
		info, err := a.CheckForUpdate()
		srv.Close()
		if err != nil {
			t.Fatalf("url %q: unexpected error %v", bad, err)
		}
		if info.HtmlURL != "" {
			t.Fatalf("url %q: must be rejected, got %q", bad, info.HtmlURL)
		}
	}
}
