package main

import (
	"bytes"
	"compress/gzip"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestNextRun(t *testing.T) {
	loc := time.FixedZone("IST", 5*3600+1800)
	at := func(d, h, m int) time.Time { return time.Date(2026, 11, d, h, m, 0, 0, loc) }
	if got := nextRun(at(13, 0, 30), "01:00"); !got.Equal(at(13, 1, 0)) {
		t.Errorf("before the time: %v", got)
	}
	if got := nextRun(at(13, 1, 0), "01:00"); !got.Equal(at(14, 1, 0)) {
		t.Errorf("exactly at the time must mean tomorrow: %v", got)
	}
	if got := nextRun(at(13, 9, 0), "junk"); !got.Equal(at(14, 1, 0)) {
		t.Errorf("bad setting falls back to 01:00: %v", got)
	}
}

func TestAlertText(t *testing.T) {
	ran := time.Now()
	ok := state{OK: true, LastRun: ran, Channels: 326, GuideChannels: 227}
	failed := state{LastRun: ran, Message: "build: exit status 1"}
	if alertText(ok, ok) != "" {
		t.Error("a normal run after a normal run is silent")
	}
	if alertText(state{}, ok) != "" {
		t.Error("the very first run is silent when it works")
	}
	if !strings.Contains(alertText(ok, failed), "FAILED: build") {
		t.Error("a failure is reported with its reason")
	}
	if !strings.Contains(alertText(failed, ok), "working again") {
		t.Error("recovery is reported")
	}
	warn := ok
	warn.Message = "channels published, but no new guide"
	if !strings.Contains(alertText(ok, warn), "no new guide") {
		t.Error("a warning is reported")
	}
}

func TestCountChannels(t *testing.T) {
	if n, err := countChannels([]byte(`[{"id":"A.in","num":100,"streams":[{"url":"u"}]},{"id":"B.in","num":101,"streams":[{"url":"u"}]}]`)); err != nil || n != 2 {
		t.Errorf("got %d, %v", n, err)
	}
	for _, bad := range []string{`[]`, `<html>`, `[{"id":"A.in","num":100,"streams":[]}]`, `[{"id":"","num":1,"streams":[{}]}]`} {
		if _, err := countChannels([]byte(bad)); err == nil {
			t.Errorf("accepted %s", bad)
		}
	}
}

func gz(s string) []byte {
	var b bytes.Buffer
	w := gzip.NewWriter(&b)
	_, _ = w.Write([]byte(s))
	_ = w.Close()
	return b.Bytes()
}

func TestCountGuide(t *testing.T) {
	if n, err := countGuide(gz(`{"A.in":[[1,2,"x"]],"B.in":[],"C.in":[[1,2,"y"],[2,3,"z"]]}`)); err != nil || n != 2 {
		t.Errorf("got %d, %v", n, err)
	}
	for _, bad := range [][]byte{[]byte("not gzip"), gz("not json"), gz(`{"A.in":[]}`)} {
		if _, err := countGuide(bad); err == nil {
			t.Errorf("accepted %q", bad)
		}
	}
}

func TestReadPlainRefusesLinksAndEmptyFiles(t *testing.T) {
	dir := t.TempDir()
	secret := filepath.Join(dir, "secret")
	_ = os.WriteFile(secret, []byte("key"), 0o600)
	link := filepath.Join(dir, "channels.json")
	if err := os.Symlink(secret, link); err != nil {
		t.Skip(err)
	}
	if _, err := readPlain(link, 1<<20); err == nil {
		t.Error("followed a link the build could have planted")
	}
	empty := filepath.Join(dir, "empty")
	_ = os.WriteFile(empty, nil, 0o644)
	if _, err := readPlain(empty, 1<<20); err == nil {
		t.Error("accepted an empty file")
	}
	if _, err := readPlain(secret, 2); err == nil {
		t.Error("accepted a file over the size limit")
	}
	if b, err := readPlain(secret, 1<<20); err != nil || string(b) != "key" {
		t.Errorf("plain file: %q, %v", b, err)
	}
}

func TestCopyTreeSkipsLinks(t *testing.T) {
	from, to := t.TempDir(), filepath.Join(t.TempDir(), "out")
	_ = os.MkdirAll(filepath.Join(from, "sub"), 0o755)
	_ = os.WriteFile(filepath.Join(from, "sub", "a.sh"), []byte("x"), 0o755)
	_ = os.Symlink("/etc/passwd", filepath.Join(from, "link"))
	if err := copyTree(from, to); err != nil {
		t.Fatal(err)
	}
	if fi, err := os.Stat(filepath.Join(to, "sub", "a.sh")); err != nil || fi.Mode().Perm() != 0o755 {
		t.Errorf("file not copied with its mode: %v", err)
	}
	if _, err := os.Lstat(filepath.Join(to, "link")); err == nil {
		t.Error("copied a link")
	}
}
