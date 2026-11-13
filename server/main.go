// Command updater rebuilds Cypher TV's channel list and programme guide once a day and publishes them to the
// repository's `dist` branch, which is what the TV reads. It replaces the scheduled GitHub workflow.
//
// It only schedules, checks and publishes. The build itself is scripts/build-dist.sh from the repository (the
// Kotlin tools plus the third-party iptv-org/epg grabber), run as an unprivileged user that cannot read the
// deploy key; this process then takes a few plain files back from it, validates them, and pushes.
package main

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	_ "time/tzdata"
)

type config struct {
	RepoURL   string // git@github.com:owner/name.git
	DataDir   string
	Tools     string // launcher of the prebuilt Kotlin tools
	DeployKey string
	Listen    string
	RunAt     string // HH:MM, local time
	Budget    int    // seconds the guide grab may take
	GrabUID   int
	GrabGID   int
	DryRun    bool // build and validate, never push
	TGToken   string
	TGChat    string
}

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func envInt(k string, def int) int {
	if n, err := strconv.Atoi(os.Getenv(k)); err == nil {
		return n
	}
	return def
}

func loadConfig() config {
	return config{
		RepoURL:   env("CYPHER_REPO_URL", "git@github.com:arnabry11/cypher-tv.git"),
		DataDir:   env("CYPHER_DATA_DIR", "/data"),
		Tools:     env("CYPHER_TOOLS", "/opt/tools/bin/tools"),
		DeployKey: env("CYPHER_DEPLOY_KEY", "/run/secrets/deploy_key"),
		Listen:    env("CYPHER_LISTEN", ":8787"),
		RunAt:     env("CYPHER_RUN_AT", "01:00"),
		Budget:    envInt("CYPHER_BUDGET_SECONDS", 3600),
		GrabUID:   envInt("CYPHER_GRAB_UID", 10001),
		GrabGID:   envInt("CYPHER_GRAB_GID", 10001),
		DryRun:    env("CYPHER_DRY_RUN", "0") == "1",
		TGToken:   os.Getenv("TELEGRAM_BOT_TOKEN"),
		TGChat:    os.Getenv("TELEGRAM_CHAT_ID"),
	}
}

// state is what survives a restart (state.json in the data directory).
type state struct {
	LastRun       time.Time `json:"lastRun"`
	LastSuccess   time.Time `json:"lastSuccess"`
	OK            bool      `json:"ok"`
	Message       string    `json:"message"`
	Channels      int       `json:"channels"`
	GuideChannels int       `json:"guideChannels"`
	Seconds       int       `json:"seconds"`
	Published     bool      `json:"published"`
}

type result struct {
	channels, guideChannels int
	published               bool
	warning                 string // the run published, but something is worth telling
}

type updater struct {
	cfg     config
	mu      sync.Mutex
	st      state
	running bool
	trigger chan string
}

func main() {
	log.SetFlags(log.LstdFlags)
	u := &updater{cfg: loadConfig(), trigger: make(chan string, 1)}
	for _, d := range []string{u.cfg.DataDir, u.path("logs")} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			log.Fatal(err)
		}
	}
	if b, err := os.ReadFile(u.path("state.json")); err == nil {
		_ = json.Unmarshal(b, &u.st)
	}
	go u.schedule()
	http.HandleFunc("/", u.handleStatus)
	http.HandleFunc("/run", u.handleRun)
	http.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) { fmt.Fprintln(w, "ok") })
	log.Printf("cypher-tv updater: daily at %s, listening on %s, dry run %v", u.cfg.RunAt, u.cfg.Listen, u.cfg.DryRun)
	log.Fatal(http.ListenAndServe(u.cfg.Listen, nil))
}

func (u *updater) path(parts ...string) string {
	return filepath.Join(append([]string{u.cfg.DataDir}, parts...)...)
}

// ---------------------------------------------------------------- scheduling

// nextRun is the first HH:MM strictly after now.
func nextRun(now time.Time, hhmm string) time.Time {
	h, m := 1, 0
	if t, err := time.Parse("15:04", hhmm); err == nil {
		h, m = t.Hour(), t.Minute()
	}
	next := time.Date(now.Year(), now.Month(), now.Day(), h, m, 0, 0, now.Location())
	if !next.After(now) {
		next = next.AddDate(0, 0, 1)
	}
	return next
}

const (
	retryAfter = time.Hour
	maxRetries = 2
)

func (u *updater) schedule() {
	retries := 0
	var timer *time.Timer
	u.mu.Lock()
	stale := time.Since(u.st.LastSuccess) > 26*time.Hour
	u.mu.Unlock()
	if stale {
		timer = time.NewTimer(time.Minute) // missed a day (new install, or the server was off): catch up now
	} else {
		timer = time.NewTimer(time.Until(nextRun(time.Now(), u.cfg.RunAt)))
	}
	for {
		reason := "schedule"
		select {
		case <-timer.C:
		case reason = <-u.trigger:
			timer.Stop()
		}
		ok := u.runOnce(reason)
		wait := time.Until(nextRun(time.Now(), u.cfg.RunAt))
		if ok {
			retries = 0
		} else if retries < maxRetries && wait > retryAfter {
			retries++
			wait = retryAfter
		} else {
			retries = 0
		}
		timer = time.NewTimer(wait)
	}
}

func (u *updater) runOnce(reason string) bool {
	u.mu.Lock()
	u.running = true
	prev := u.st
	u.mu.Unlock()

	start := time.Now()
	logPath := u.path("logs", start.Format("2006-01-02T150405")+".log")
	logFile, err := os.Create(logPath)
	if err != nil {
		log.Printf("cannot create log: %v", err)
		logFile = os.Stderr
	} else {
		defer logFile.Close()
	}
	out := io.MultiWriter(logFile, os.Stdout)
	fmt.Fprintf(out, "== run (%s) at %s\n", reason, start.Format(time.RFC3339))

	res, err := u.run(out, prev)
	st := state{LastRun: start, LastSuccess: prev.LastSuccess, Seconds: int(time.Since(start).Seconds())}
	if err != nil {
		st.Message = err.Error()
		st.Channels, st.GuideChannels = prev.Channels, prev.GuideChannels
		fmt.Fprintf(out, "== FAILED: %v\n", err)
	} else {
		st.OK, st.LastSuccess = true, start
		st.Channels, st.GuideChannels, st.Published = res.channels, res.guideChannels, res.published
		st.Message = res.warning
		fmt.Fprintf(out, "== ok: %d channels, guide for %d, published %v\n", res.channels, res.guideChannels, res.published)
	}
	u.mu.Lock()
	u.st, u.running = st, false
	u.mu.Unlock()
	if b, e := json.MarshalIndent(st, "", "  "); e == nil {
		_ = os.WriteFile(u.path("state.json"), b, 0o644)
	}
	u.pruneLogs(14)
	if msg := alertText(prev, st); msg != "" {
		if e := u.telegram(msg); e != nil {
			log.Printf("telegram: %v", e)
		}
	}
	return err == nil
}

// alertText decides what, if anything, is worth a message: a failure, a warning, or the first success after a failure.
func alertText(prev, cur state) string {
	switch {
	case !cur.OK:
		return "Cypher TV update FAILED: " + cur.Message
	case cur.Message != "":
		return fmt.Sprintf("Cypher TV update: %s (%d channels, guide for %d)", cur.Message, cur.Channels, cur.GuideChannels)
	case !prev.OK && !prev.LastRun.IsZero():
		return fmt.Sprintf("Cypher TV update is working again: %d channels, guide for %d", cur.Channels, cur.GuideChannels)
	}
	return ""
}

func (u *updater) pruneLogs(keep int) {
	names, _ := filepath.Glob(u.path("logs", "*.log"))
	sort.Strings(names)
	for i := 0; i < len(names)-keep; i++ {
		_ = os.Remove(names[i])
	}
}

// ---------------------------------------------------------------- one run

func (u *updater) run(out io.Writer, prev state) (result, error) {
	var res result
	repo, src, stage := u.path("repo"), u.path("work", "src"), u.path("stage")

	// 1. The repository at today's main: data files and scripts. Owned by us; the build never writes here.
	if err := u.syncRepo(out, repo); err != nil {
		return res, fmt.Errorf("fetching the repository: %w", err)
	}

	// 2. A copy the build may scribble on. build/ (the grabber checkout and its node_modules) is kept between runs.
	for _, d := range []string{"data", "scripts", "dist"} {
		if err := os.RemoveAll(filepath.Join(src, d)); err != nil {
			return res, err
		}
	}
	if err := os.MkdirAll(u.path("work", "home"), 0o755); err != nil {
		return res, err
	}
	for _, d := range []string{"data", "scripts"} {
		if err := copyTree(filepath.Join(repo, d), filepath.Join(src, d)); err != nil {
			return res, fmt.Errorf("preparing the build copy: %w", err)
		}
	}
	if os.Geteuid() == 0 {
		// The build user owns its working area. build/ and home/ below it are its own already (it created them).
		for _, d := range []string{u.path("work"), u.path("work", "home"), src} {
			if err := os.Lchown(d, u.cfg.GrabUID, u.cfg.GrabGID); err != nil {
				return res, err
			}
		}
		for _, d := range []string{"data", "scripts"} {
			if err := chownTree(filepath.Join(src, d), u.cfg.GrabUID, u.cfg.GrabGID); err != nil {
				return res, err
			}
		}
	}

	// 3. The build, without our credentials.
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(u.cfg.Budget)*time.Second+20*time.Minute)
	defer cancel()
	build := exec.CommandContext(ctx, "sh", "scripts/build-dist.sh")
	build.Dir = src
	build.Env = []string{
		"PATH=" + os.Getenv("PATH"), "HOME=" + u.path("work", "home"), "LANG=C.UTF-8", "TZ=" + os.Getenv("TZ"),
		"JAVA_HOME=" + os.Getenv("JAVA_HOME"), "CYPHER_TOOLS=" + u.cfg.Tools, "OUT=dist",
		"BUDGET_SECONDS=" + strconv.Itoa(u.cfg.Budget), "npm_config_update_notifier=false",
	}
	build.Stdout, build.Stderr = out, out
	build.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if os.Geteuid() == 0 {
		build.SysProcAttr.Credential = &syscall.Credential{Uid: uint32(u.cfg.GrabUID), Gid: uint32(u.cfg.GrabGID)}
	}
	build.Cancel = func() error { return syscall.Kill(-build.Process.Pid, syscall.SIGKILL) } // the whole group
	build.WaitDelay = 10 * time.Second
	if err := build.Run(); err != nil {
		return res, fmt.Errorf("build: %w", err)
	}

	// 4. Take back only plain files, and only if they look right.
	channels, err := readPlain(filepath.Join(src, "dist", "channels.json"), 20<<20)
	if err != nil {
		return res, err
	}
	if res.channels, err = countChannels(channels); err != nil {
		return res, fmt.Errorf("channels.json: %w", err)
	}
	if prev.Channels > 0 && res.channels*2 < prev.Channels {
		return res, fmt.Errorf("only %d channels, down from %d: not publishing", res.channels, prev.Channels)
	}
	numbers, err := readPlain(filepath.Join(src, "data", "numbers.json"), 5<<20)
	if err != nil {
		return res, err
	}
	var nums map[string]int
	if err := json.Unmarshal(numbers, &nums); err != nil || len(nums) < res.channels {
		return res, fmt.Errorf("numbers.json does not cover the channels (%v)", err)
	}
	files := map[string][]byte{"channels.json": channels}
	if report, err := readPlain(filepath.Join(src, "dist", "match-report.md"), 5<<20); err == nil {
		files["match-report.md"] = report
	}
	guide, gerr := readPlain(filepath.Join(src, "dist", "epg.json.gz"), 50<<20)
	if gerr == nil {
		res.guideChannels, gerr = countGuide(guide)
	}
	switch {
	case gerr != nil:
		res.guideChannels = prev.GuideChannels
		res.warning = "channels published, but no new guide (" + gerr.Error() + ")"
	case prev.GuideChannels > 0 && res.guideChannels*2 < prev.GuideChannels:
		res.warning = fmt.Sprintf("guide covers only %d channels, down from %d; keeping the previous guide", res.guideChannels, prev.GuideChannels)
		res.guideChannels = prev.GuideChannels
	default:
		files["epg.json.gz"] = guide
	}

	if u.cfg.DryRun {
		fmt.Fprintln(out, "dry run: not pushing")
		return res, nil
	}

	// 5. Publish. New channel numbers go to main first, so a number handed out is never lost.
	if err := os.WriteFile(filepath.Join(repo, "data", "numbers.json"), numbers, 0o644); err != nil {
		return res, err
	}
	if changed, _ := u.gitOut(repo, "status", "--porcelain", "data/numbers.json"); strings.TrimSpace(changed) != "" {
		if err := u.git(out, repo, "commit", "-q", "-m", "Assign channel numbers", "data/numbers.json"); err != nil {
			return res, err
		}
		if err := u.git(out, repo, "push", "-q", "origin", "HEAD:main"); err != nil {
			return res, fmt.Errorf("pushing channel numbers: %w", err)
		}
	}
	if err := os.RemoveAll(stage); err != nil {
		return res, err
	}
	if err := os.MkdirAll(stage, 0o755); err != nil {
		return res, err
	}
	for name, b := range files {
		if err := os.WriteFile(filepath.Join(stage, name), b, 0o644); err != nil {
			return res, err
		}
	}
	publish := exec.Command("sh", "scripts/publish-dist.sh", stage)
	publish.Dir = repo
	publish.Env = u.gitEnv()
	publish.Stdout, publish.Stderr = out, out
	if err := publish.Run(); err != nil {
		return res, fmt.Errorf("publishing: %w", err)
	}
	res.published = true
	return res, nil
}

const gitName, gitEmail = "cypher-tv-updater", "arnabry11@users.noreply.github.com"

func (u *updater) gitEnv() []string {
	ssh := "ssh -i " + u.cfg.DeployKey + " -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=" + u.path("known_hosts")
	return []string{
		"PATH=" + os.Getenv("PATH"), "HOME=" + u.path("home"), "GIT_SSH_COMMAND=" + ssh, "GIT_TERMINAL_PROMPT=0",
		"GIT_USER_NAME=" + gitName, "GIT_USER_EMAIL=" + gitEmail,
		"GIT_AUTHOR_NAME=" + gitName, "GIT_AUTHOR_EMAIL=" + gitEmail, "GIT_COMMITTER_NAME=" + gitName, "GIT_COMMITTER_EMAIL=" + gitEmail,
	}
}

func (u *updater) git(out io.Writer, dir string, args ...string) error {
	c := exec.Command("git", args...)
	c.Dir, c.Env, c.Stdout, c.Stderr = dir, u.gitEnv(), out, out
	if err := c.Run(); err != nil {
		return fmt.Errorf("git %s: %w", args[0], err)
	}
	return nil
}

func (u *updater) gitOut(dir string, args ...string) (string, error) {
	c := exec.Command("git", args...)
	c.Dir, c.Env = dir, u.gitEnv()
	b, err := c.Output()
	return string(b), err
}

func (u *updater) syncRepo(out io.Writer, repo string) error {
	_ = os.MkdirAll(u.path("home"), 0o700)
	if _, err := os.Stat(filepath.Join(repo, ".git")); err != nil {
		_ = os.RemoveAll(repo)
		return u.git(out, u.cfg.DataDir, "clone", "-q", "--depth", "1", "--branch", "main", u.cfg.RepoURL, repo)
	}
	if err := u.git(out, repo, "fetch", "-q", "--depth", "1", "origin", "main"); err != nil {
		return err
	}
	if err := u.git(out, repo, "reset", "-q", "--hard", "FETCH_HEAD"); err != nil {
		return err
	}
	return u.git(out, repo, "clean", "-qfdx")
}

// ---------------------------------------------------------------- files and checks

// readPlain reads a regular file (never a link the build could have pointed somewhere else) of bounded size.
func readPlain(path string, max int64) ([]byte, error) {
	fi, err := os.Lstat(path)
	if err != nil {
		return nil, fmt.Errorf("%s was not produced", filepath.Base(path))
	}
	if !fi.Mode().IsRegular() || fi.Size() == 0 || fi.Size() > max {
		return nil, fmt.Errorf("%s is not a plain file of a sensible size", filepath.Base(path))
	}
	return os.ReadFile(path)
}

func countChannels(b []byte) (int, error) {
	var list []struct {
		ID      string            `json:"id"`
		Num     int               `json:"num"`
		Streams []json.RawMessage `json:"streams"`
	}
	if err := json.Unmarshal(b, &list); err != nil {
		return 0, err
	}
	for _, c := range list {
		if c.ID == "" || c.Num <= 0 || len(c.Streams) == 0 {
			return 0, fmt.Errorf("channel %q is incomplete", c.ID)
		}
	}
	if len(list) == 0 {
		return 0, errors.New("no channels")
	}
	return len(list), nil
}

// countGuide returns how many channels the gzipped guide has programmes for.
func countGuide(gz []byte) (int, error) {
	r, err := gzip.NewReader(bytes.NewReader(gz))
	if err != nil {
		return 0, errors.New("guide is not gzip")
	}
	var m map[string][]json.RawMessage
	if err := json.NewDecoder(io.LimitReader(r, 200<<20)).Decode(&m); err != nil {
		return 0, errors.New("guide is not valid JSON")
	}
	n := 0
	for _, v := range m {
		if len(v) > 0 {
			n++
		}
	}
	if n == 0 {
		return 0, errors.New("guide is empty")
	}
	return n, nil
}

func copyTree(from, to string) error {
	return filepath.Walk(from, func(p string, fi os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(from, p)
		dst := filepath.Join(to, rel)
		switch {
		case fi.IsDir():
			return os.MkdirAll(dst, 0o755)
		case fi.Mode().IsRegular():
			b, err := os.ReadFile(p)
			if err != nil {
				return err
			}
			return os.WriteFile(dst, b, fi.Mode().Perm())
		}
		return nil // links and specials are not part of data/ or scripts/
	})
}

// chownTree gives root and everything under it to uid:gid.
func chownTree(root string, uid, gid int) error {
	return filepath.Walk(root, func(p string, _ os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		return os.Lchown(p, uid, gid)
	})
}

// ---------------------------------------------------------------- telegram

func (u *updater) telegram(text string) error {
	if u.cfg.TGToken == "" || u.cfg.TGChat == "" {
		return nil
	}
	c := http.Client{Timeout: 20 * time.Second}
	resp, err := c.PostForm("https://api.telegram.org/bot"+u.cfg.TGToken+"/sendMessage",
		url.Values{"chat_id": {u.cfg.TGChat}, "text": {text}, "disable_web_page_preview": {"true"}})
	if err != nil {
		return errors.New("request failed") // the error text would contain the token
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return nil
}

// ---------------------------------------------------------------- http

func (u *updater) handleRun(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	select {
	case u.trigger <- "manual":
	default: // one is already queued
	}
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

func (u *updater) handleStatus(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	u.mu.Lock()
	st, running := u.st, u.running
	u.mu.Unlock()
	status := "never run"
	switch {
	case running:
		status = "running now"
	case st.OK && st.Message != "":
		status = "ok, with a warning: " + st.Message
	case st.OK:
		status = "ok"
	case !st.LastRun.IsZero():
		status = "FAILED: " + st.Message
	}
	when := func(t time.Time) string {
		if t.IsZero() {
			return "never"
		}
		return t.Local().Format("Mon 2 Jan 15:04")
	}
	tail := ""
	if names, _ := filepath.Glob(u.path("logs", "*.log")); len(names) > 0 {
		sort.Strings(names)
		if b, err := os.ReadFile(names[len(names)-1]); err == nil {
			if len(b) > 8000 {
				b = b[len(b)-8000:]
			}
			tail = string(b)
		}
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprintf(w, `<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><title>Cypher TV updater</title>
<style>body{font:15px system-ui;margin:2em auto;max-width:52em;padding:0 1em}pre{background:#f3f3f3;padding:1em;overflow:auto}td{padding:.2em 1em .2em 0}</style>
<h1>Cypher TV updater</h1><table>
<tr><td>Status<td><b>%s</b>
<tr><td>Last run<td>%s (%d s)
<tr><td>Last success<td>%s
<tr><td>Channels<td>%d, guide for %d
<tr><td>Next run<td>%s%s
</table><form method=post action=/run><p><button>Run now</button></form><h2>Latest log</h2><pre>%s</pre>`,
		html.EscapeString(status), when(st.LastRun), st.Seconds, when(st.LastSuccess), st.Channels, st.GuideChannels,
		when(nextRun(time.Now(), u.cfg.RunAt)), map[bool]string{true: " (dry run: nothing is pushed)"}[u.cfg.DryRun], html.EscapeString(tail))
}
