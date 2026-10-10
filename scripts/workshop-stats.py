#!/usr/bin/env python3
"""Track how the Workshop page performs: visitors, subscribers, engagement, installs that reach GitHub.

    scripts/workshop-stats.py collect                 # one snapshot + backfill (the systemd timer runs it hourly)
    scripts/workshop-stats.py backfill                # refresh the history sources only
    scripts/workshop-stats.py mark "proposal b live"  # note the moment the page changed (right after the upload)
    scripts/workshop-stats.py report [--at <ISO time>|<mark label>] [--days 3] [--trend-days 8]
    scripts/workshop-stats.py changes                 # every recorded description change, with its similarity

Built for the description restyle (2026-10-10, proposals in docs/workshop/proposals/): the restyle is judged by
what the page converts, not by what it draws, so the report compares per-day rates in the days before and after the
change, each day against the spread of the days before it.

Sources, all public or the maintainer's own gh login, no Steam login:
- Steam ISteamRemoteStorage/GetPublishedFileDetails: unique visitors (`views`, lifetime), current / lifetime
  subscriptions and favourites, time_updated (= a release), title, description.
- The Workshop page HTML: rating count, star image, comment count.
- GitHub: stars, installer / zip / uninstaller download counts summed over every release, 14-day referrers, daily views.
- Discord invite counts (approximate members / online).

Installs are mostly invisible. The page's main path since 2026-09-27 is the helper window (enable the mod, run the
install.ps1 / install.bash the Workshop item ships, which installs from the item's own pzopt-classes folder); since
2026-10-07 01:47 the page also shows a by-hand copy from that folder (73-install-manual.avif). Neither reaches GitHub,
and the GitHub installers too prefer the Workshop copy when one is on disk (only install.ps1 / install.sh itself is
downloaded). The GitHub installer downloads count the page's "No window?" one-liner alone (reworded from "Without the
window" by the 2026-10-06 AVIF restyle, 38fb88b2): a fallback-path count, not installs. Zip downloads are mostly GitHub
users without a Workshop copy and the in-game updater, not the page either.

History (`backfill`, kept in ~/.local/share/pzopt/workshop-backfill.json, so a change before the first snapshot can
still be judged by day): every release's installer / zip downloads, spread over the hours it was the latest release
(/releases/latest/download/ serves the latest); every comment's timestamp (the comment render endpoint, newest first,
fetched until the known ones); GitHub's daily views (the API keeps 14 days; each run merges them, so the store keeps
them all); Steam counter readings found in older sessions (STEAM_POINTS). Steam keeps no public history of visitors or
subscribers (and the Wayback Machine had no copy on 2026-10-10), so those start with the first snapshot.

Snapshots append to ~/.local/share/pzopt/workshop-stats.jsonl; each distinct description is kept once under
~/.local/share/pzopt/workshop-descriptions/<hash>.txt. A release changes the description's build line and sha256; the
hash ignores those (commit / revision / sha / file count / build number), so a release alone is not a description change.
New! cards still are: `changes` lists each change with its similarity to the previous text, and `report` without --at
takes the last `mark`, else the last change below 0.7 similarity.
"""
import argparse, bisect, datetime as dt, difflib, hashlib, json, re, statistics, subprocess, sys, urllib.parse, urllib.request
from pathlib import Path

ITEM = "3805285544"
REPO = "xD3I/PZ_Optimization"
DISCORD = "WNeQqYZ4T"
DATA = Path.home() / ".local/share/pzopt"
LOG = DATA / "workshop-stats.jsonl"
DESCS = DATA / "workshop-descriptions"
BACKFILL = DATA / "workshop-backfill.json"
CREATOR = "76561198030422376"
# Steam counters read by earlier sessions, before the hourly snapshots began (from their transcripts).
STEAM_POINTS = [
    {"t": "2026-09-24T18:02:03+00:00", "visitors": 47037, "subs": 19495, "lifetime_subs": 22819, "favs": 3259,
     "lifetime_favs": 3330},
]
UA = {"User-Agent": "pzopt-workshop-stats (github.com/xD3I/PZ_Optimization)"}


def fetch(url, data=None):
    req = urllib.request.Request(url, data=urllib.parse.urlencode(data).encode() if data else None, headers=UA)
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8", "replace")


def gh(path):
    out = subprocess.run(["gh", "api", path], capture_output=True, text=True, timeout=60)
    if out.returncode:
        raise RuntimeError(out.stderr.strip())
    return json.loads(out.stdout)


def num(s):
    return int(re.sub(r"[^\d]", "", s))


def normalize(desc):
    """The description without what every release rewrites."""
    d = re.sub(r"\b[0-9a-f]{8,64}\b", "#", desc)
    d = re.sub(r"\b\d+ files\b", "# files", d)
    return re.sub(r"Build 4\d(\.\d+)*", "Build #", d)


def steam():
    d = json.loads(fetch("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/",
                         {"itemcount": 1, "publishedfileids[0]": ITEM}))["response"]["publishedfiledetails"][0]
    desc = d.get("description", "")
    h = hashlib.sha256(normalize(desc).encode()).hexdigest()[:16]
    DESCS.mkdir(parents=True, exist_ok=True)
    if not (DESCS / f"{h}.txt").exists():
        (DESCS / f"{h}.txt").write_text(desc)
    return {"visitors": d["views"], "subs": d["subscriptions"], "lifetime_subs": d["lifetime_subscriptions"],
            "favs": d["favorited"], "lifetime_favs": d["lifetime_favorited"], "time_updated": d["time_updated"],
            "title": d.get("title", ""), "desc_hash": h, "desc_len": len(desc)}


def page():
    h = fetch(f"https://steamcommunity.com/sharedfiles/filedetails/?id={ITEM}")
    r = re.search(r'numRatings">([\d,]+)', h)
    s = re.search(r'fileRatingDetails"><img src="[^"]*/([\w-]+)_large\.png', h)
    c = re.search(r'_totalcount">([\d,]+)</span>', h)
    return {"ratings": num(r.group(1)) if r else None, "stars": s.group(1) if s else None,
            "comments": num(c.group(1)) if c else None}


def github():
    repo = gh(f"repos/{REPO}")
    dl = {"installer": 0, "zip": 0, "uninstaller": 0}
    page_no = 1
    while True:
        rel = gh(f"repos/{REPO}/releases?per_page=100&page={page_no}")
        for a in (x for r in rel for x in r["assets"]):
            n = a["name"]
            k = "uninstaller" if n.startswith("uninstall") else "installer" if n.startswith("install") else \
                "zip" if n.endswith(".zip") else None
            if k:
                dl[k] += a["download_count"]
        if len(rel) < 100:
            break
        page_no += 1
    refs = {r["referrer"]: [r["count"], r["uniques"]] for r in gh(f"repos/{REPO}/traffic/popular/referrers")}
    views = {v["timestamp"][:10]: [v["count"], v["uniques"]] for v in gh(f"repos/{REPO}/traffic/views")["views"]}
    return {"stars": repo["stargazers_count"], "downloads": dl, "referrers_14d": refs, "views_daily": views}


def discord():
    d = json.loads(fetch(f"https://discord.com/api/v9/invites/{DISCORD}?with_counts=true"))
    return {"members": d.get("approximate_member_count"), "online": d.get("approximate_presence_count")}


def collect(_):
    snap = {"t": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")}
    for name, fn in (("steam", steam), ("page", page), ("github", github), ("discord", discord)):
        try:
            snap[name] = fn()
        except Exception as e:  # one source down must not lose the others
            snap[name] = {"error": str(e)[:300]}
    DATA.mkdir(parents=True, exist_ok=True)
    with LOG.open("a") as f:
        f.write(json.dumps(snap, separators=(",", ":")) + "\n")
    s = snap.get("steam", {})
    print(f"{snap['t']} visitors={s.get('visitors')} subs={s.get('subs')} lifetime={s.get('lifetime_subs')} "
          f"desc={s.get('desc_hash')} errors={[k for k, v in snap.items() if isinstance(v, dict) and 'error' in v]}")
    backfill(None)


def backfill(_):
    store = json.loads(BACKFILL.read_text()) if BACKFILL.exists() else {}
    errors = []
    try:  # releases: cumulative downloads per release, re-read in full (download counts keep growing)
        rels, page_no = [], 1
        while True:
            batch = gh(f"repos/{REPO}/releases?per_page=100&page={page_no}")
            rels += batch
            if len(batch) < 100:
                break
            page_no += 1
        rows = []
        for r in rels:
            if r["draft"] or r["prerelease"] or not r.get("published_at"):
                continue
            d = {"installer": 0, "zip": 0, "uninstaller": 0}
            for a in r["assets"]:
                n = a["name"]
                k = "uninstaller" if n.startswith("uninstall") else "installer" if n.startswith("install") else \
                    "zip" if n.endswith(".zip") else None
                if k:
                    d[k] += a["download_count"]
            rows.append({"t": r["published_at"], "tag": r["tag_name"], **d})
        store["releases"] = sorted(rows, key=lambda r: r["t"])
        store["releases_read"] = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")
    except Exception as e:
        errors.append(f"releases: {e}")
    try:  # comments: newest first, until a page reaches the newest known one
        known = set(store.get("comments", []))
        newest = max(known, default=0)
        start, total = 0, None
        while True:
            d = json.loads(fetch(f"https://steamcommunity.com/comment/PublishedFile_Public/render/{CREATOR}/{ITEM}/"
                                 f"?start={start}&count=100"))
            total = d.get("total_count", 0)
            ts = [int(x) for x in re.findall(r'data-timestamp="(\d+)"', d.get("comments_html", ""))]
            known.update(ts)
            start += 100
            if not ts or min(ts) <= newest or start >= total:
                break
        store["comments"] = sorted(known)
        store["comments_total"] = total
    except Exception as e:
        errors.append(f"comments: {e}")
    try:  # GitHub daily views: the API's 14 days merged into everything stored before
        views = store.get("github_views", {})
        for v in gh(f"repos/{REPO}/traffic/views")["views"]:
            views[v["timestamp"][:10]] = [v["count"], v["uniques"]]
        store["github_views"] = dict(sorted(views.items()))
    except Exception as e:
        errors.append(f"github views: {e}")
    DATA.mkdir(parents=True, exist_ok=True)
    BACKFILL.write_text(json.dumps(store, separators=(",", ":")))
    print(f"backfill: {len(store.get('releases', []))} releases, {len(store.get('comments', []))} comments, "
          f"{len(store.get('github_views', {}))} days of GitHub views" + (f"; errors {errors}" if errors else ""))


def history_daily():
    """UTC day -> {metric: value} from the backfill store."""
    store = json.loads(BACKFILL.read_text()) if BACKFILL.exists() else {}
    days = {}
    put = lambda day, k, v: days.setdefault(day, {}).__setitem__(k, days.get(day, {}).get(k, 0) + v)
    rels = store.get("releases", [])
    end = dt.datetime.fromisoformat(store.get("releases_read", dt.datetime.now(dt.timezone.utc).isoformat()))
    for i, r in enumerate(rels):  # a release's downloads spread evenly over the hours it was the latest
        a = dt.datetime.fromisoformat(r["t"].replace("Z", "+00:00"))
        b = dt.datetime.fromisoformat(rels[i + 1]["t"].replace("Z", "+00:00")) if i + 1 < len(rels) else end
        span = (b - a).total_seconds()
        put(a.date().isoformat(), "releases", 1)
        cur = a
        while span > 0 and cur < b:
            nxt = min(b, dt.datetime.combine(cur.date() + dt.timedelta(days=1), dt.time(), dt.timezone.utc))
            for k in ("installer", "zip"):
                put(cur.date().isoformat(), f"github {k} dl", r[k] * (nxt - cur).total_seconds() / span)
            cur = nxt
    for t in store.get("comments", []):
        put(dt.datetime.fromtimestamp(t, dt.timezone.utc).date().isoformat(), "comments", 1)
    for day, (count, uniques) in store.get("github_views", {}).items():
        put(day, "github views", count)
        put(day, "github visitors", uniques)
    return days, end.date().isoformat()


def history_report(change, days_each, trend_days, baseline=False):
    """Whole UTC days before / after the change day, the after days against the trend of the days before
    (baseline: no change, the last days only)."""
    import math
    days, partial = history_daily()
    if not days:
        print("\nno backfill yet: run `workshop-stats.py backfill`")
        return
    cday = dt.datetime.fromtimestamp(change, dt.timezone.utc).date()
    span = lambda lo, hi: [(cday + dt.timedelta(days=i)).isoformat() for i in range(lo, hi)]
    shown = span(-days_each, 1) if baseline else span(-days_each, 0) + span(1, days_each + 1)
    metrics = ["github installer dl", "github zip dl", "comments", "github views", "github visitors", "releases"]
    print(f"\nhistory by UTC day ({'baseline' if baseline else f'the change day {cday} left out'}; "
          f"the last day {partial} is partial):")
    print(" " * 22 + "".join(f"{d[5:]:>8s}" for d in shown))
    for k in metrics:
        print(f"  {k:20s}" + "".join(f"{fmt(days[d][k]) if k in days.get(d, {}) else '-':>8s}" for d in shown))
    if baseline:
        return
    print(f"\nafter vs the log-linear trend of the {trend_days} days before (the counts were still falling after launch):")
    for k in metrics[:-1]:
        pre = [(i, math.log(days[d][k])) for i, d in enumerate(span(-trend_days, 0), -trend_days)
               if days.get(d, {}).get(k, 0) > 0]
        post = [(i, days[d][k]) for i, d in enumerate(span(1, days_each + 1), 1)
                if k in days.get(d, {}) and d < partial]
        if len(pre) < 3 or not post:
            print(f"  {k:20s} not enough days")
            continue
        n = len(pre)
        mx, my = sum(x for x, _ in pre) / n, sum(y for _, y in pre) / n
        b = sum((x - mx) * (y - my) for x, y in pre) / sum((x - mx) ** 2 for x, _ in pre)
        a = my - b * mx
        noise = (sum((y - a - b * x) ** 2 for x, y in pre) / max(1, n - 2)) ** .5
        pred = [math.exp(a + b * x) for x, _ in post]
        act = [v for _, v in post]
        last2 = [days[d][k] for d in span(-2, 0) if k in days.get(d, {})]
        print(f"  {k:20s} trend {100 * (math.exp(b) - 1):+.0f} %/day (day noise ±{100 * noise:.0f} %): after "
              f"{fmt(sum(act) / len(act))}/day vs trend {fmt(sum(pred) / len(pred))} ({100 * (sum(act) / sum(pred) - 1):+.0f} %), "
              f"vs the last 2 days before {fmt(sum(last2) / len(last2)) if last2 else '-'}")
    print("  (installer dl = the page's GitHub one-liner only; the helper window and the by-hand copy never reach GitHub)")


def load():
    if not LOG.exists():
        sys.exit(f"no snapshots yet: {LOG}")
    rows = [json.loads(l) for l in LOG.read_text().splitlines() if l.strip()]
    for r in rows:
        r["ts"] = dt.datetime.fromisoformat(r["t"]).timestamp()
    return rows


def mark(a):
    DATA.mkdir(parents=True, exist_ok=True)
    with LOG.open("a") as f:
        f.write(json.dumps({"t": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"), "mark": a.label}) + "\n")
    collect(a)


def desc_changes(rows):
    out, prev = [], None
    for r in rows:
        h = r.get("steam", {}).get("desc_hash")
        if not h:
            continue
        if prev and h != prev[1]:
            old, new = (normalize((DESCS / f"{x}.txt").read_text()) for x in (prev[1], h))
            out.append((r["ts"], h, difflib.SequenceMatcher(None, old, new, autojunk=False).ratio()))
        prev = (r["ts"], h)
    return out


def changes(_):
    rows = load()
    for r in rows:
        if "mark" in r:
            print(f"{r['t']}  mark: {r['mark']}")
    for ts, h, sim in desc_changes(rows):
        print(f"{iso(ts)}  description -> {h} similarity {sim:.2f}")


def iso(ts):
    return dt.datetime.fromtimestamp(ts, dt.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")


# Cumulative counters; per-day rates come from their interpolated values at the day boundaries.
COUNTERS = {
    "visitors": ("steam", "visitors"), "new subs": ("steam", "lifetime_subs"), "net subs": ("steam", "subs"),
    "new favourites": ("steam", "lifetime_favs"), "ratings": ("page", "ratings"), "comments": ("page", "comments"),
    "github installer dl": ("github", "downloads", "installer"), "github zip dl": ("github", "downloads", "zip"),
    "github uninstaller dl": ("github", "downloads", "uninstaller"), "github stars": ("github", "stars"),
    "discord members": ("discord", "members"),
}


def series(rows, path):
    xs, ys = [], []
    for r in rows:
        v = r
        for k in path:
            v = v.get(k) if isinstance(v, dict) else None
        if isinstance(v, (int, float)):
            xs.append(r["ts"]), ys.append(v)
    return xs, ys


def at(xs, ys, t):
    """Linear interpolation; None outside the sampled span."""
    if not xs or t < xs[0] or t > xs[-1]:
        return None
    i = bisect.bisect_left(xs, t)
    if xs[i] == t or i == 0:
        return ys[i]
    x0, x1, y0, y1 = xs[i - 1], xs[i], ys[i - 1], ys[i]
    return y0 + (y1 - y0) * (t - x0) / (x1 - x0)


def day_metrics(rows, t0, t1):
    m = {}
    for name, path in COUNTERS.items():
        xs, ys = series(rows, path)
        a, b = at(xs, ys, t0), at(xs, ys, t1)
        m[name] = None if a is None or b is None else b - a
    if m["new subs"] is not None and m["net subs"] is not None:
        m["unsubscribes"] = m["new subs"] - m["net subs"]
    ratio = lambda n, d, k: None if m.get(n) is None or not m.get(d) else k * m[n] / m[d]
    m["subs / 100 visitors"] = ratio("new subs", "visitors", 100)
    m["favs / 100 visitors"] = ratio("new favourites", "visitors", 100)
    m["one-liner dl / new sub"] = ratio("github installer dl", "new subs", 1)
    m["unsubs / new sub"] = ratio("unsubscribes", "new subs", 1)
    m["comments / 1k visitors"] = ratio("comments", "visitors", 1000)
    rel = sorted({r["steam"]["time_updated"] for r in rows if t0 < r.get("steam", {}).get("time_updated", 0) <= t1})
    m["releases"] = len(rel)
    return m


def fmt(v):
    return "-" if v is None else f"{v:,.0f}" if abs(v) >= 100 or float(v).is_integer() else f"{v:.2f}" if abs(v) < 10 else f"{v:.1f}"


def report(a):
    rows = [r for r in load() if "mark" not in r]
    marks = [r for r in load() if "mark" in r]
    if not rows:
        sys.exit("no snapshots yet")
    first, last = rows[0]["ts"], rows[-1]["ts"]
    change, why = None, None
    if a.at:
        mk = [r for r in marks if r["mark"] == a.at]
        if mk:
            change, why = mk[-1]["ts"], f"mark '{a.at}'"
        else:
            change, why = dt.datetime.fromisoformat(a.at).timestamp(), "--at"
    elif marks:
        change, why = marks[-1]["ts"], f"mark '{marks[-1]['mark']}'"
    else:
        big = [c for c in desc_changes(rows) if c[2] < 0.7]
        if big:
            change, why = big[-1][0], f"description change (similarity {big[-1][2]:.2f})"
    day = 86400
    print(f"snapshots {len(rows)}, {iso(first)} .. {iso(last)}")
    steam_points(rows)
    if change is None:
        n = min(a.days, int((last - first) // day))
        print("no description change recorded yet: baseline only")
        if n < 1:
            print(f"less than a day of snapshots; totals over {(last - first) / 3600:.1f} h:")
            for k, v in day_metrics(rows, first, last).items():
                print(f"  {k:24s} {fmt(v)}")
        else:
            windows = [(last - (i + 1) * day, last - i * day) for i in reversed(range(n))]
            table([(f"day -{n - i}", w) for i, w in enumerate(windows)], rows)
        history_report(last, a.days, a.trend_days, baseline=True)
        return
    print(f"change at {iso(change)} ({why})")
    before = [(change - (i + 1) * day, change - i * day) for i in reversed(range(a.days))]
    after = [(change + i * day, change + (i + 1) * day) for i in range(a.days)]
    before = [w for w in before if w[0] >= first]
    after = [w for w in after if w[1] <= last]
    if not before:
        print("\nno snapshots before the change: Steam visitors / subscribers cannot be compared, the history below can")
    else:
        cols = [(f"-{len(before) - i}d", w) for i, w in enumerate(before)] + \
               [(f"+{i + 1}d", w) for i, w in enumerate(after)]
        ms = table(cols, rows)
        if after:
            print("\nafter vs before (mean of whole days; 'range' = min..max of the days before, i.e. the normal spread):")
            mb, ma = ms[:len(before)], ms[len(before):]
            for k in ms[0]:
                if k == "releases":
                    continue
                b = [m[k] for m in mb if m[k] is not None]
                f = [m[k] for m in ma if m[k] is not None]
                if not b or not f:
                    continue
                mean_b, mean_a = statistics.mean(b), statistics.mean(f)
                pct = f"{100 * (mean_a - mean_b) / mean_b:+.0f} %" if mean_b else ""
                inside = min(b) <= mean_a <= max(b)
                print(f"  {k:24s} {fmt(mean_b):>8s} -> {fmt(mean_a):>8s} {pct:>7s}  range {fmt(min(b))}..{fmt(max(b))}"
                      f"{'' if inside or len(b) < 2 else '  OUTSIDE'}")
        else:
            print(f"\nno whole day after the change yet ({(last - change) / 3600:.1f} h of data)")
        refs_at = lambda t: next((r["github"].get("referrers_14d", {}).get("steamcommunity.com")
                                  for r in reversed(rows) if r["ts"] <= t and "referrers_14d" in r.get("github", {})), None)
        pre, post = refs_at(change), refs_at(change + 14 * day) if last >= change + 14 * day else None
        print(f"\nGitHub visits from steamcommunity.com, 14-day totals [views, uniques]: before {pre}, "
              f"after {post if post else 'needs 14 days after the change'}")
    history_report(change, a.days, a.trend_days)


def steam_points(rows):
    """Average daily Steam rates from the older readings (STEAM_POINTS) to the first snapshot."""
    s0 = next((r for r in rows if "visitors" in r.get("steam", {})), None)
    for p in STEAM_POINTS:
        if not s0:
            break
        t = dt.datetime.fromisoformat(p["t"]).timestamp()
        d = (s0["ts"] - t) / 86400
        rate = {k: (s0["steam"][k] - p[k]) / d for k in ("visitors", "lifetime_subs", "subs", "lifetime_favs")}
        print(f"Steam {iso(t)} .. first snapshot ({d:.1f} days), per day: visitors {fmt(rate['visitors'])}, "
              f"new subs {fmt(rate['lifetime_subs'])}, net subs {fmt(rate['subs'])}, new favourites {fmt(rate['lifetime_favs'])}")


def table(cols, rows):
    ms = [day_metrics(rows, *w) for _, w in cols]
    print("\n" + " " * 26 + "".join(f"{c:>10s}" for c, _ in cols))
    for k in ms[0]:
        print(f"  {k:24s}" + "".join(f"{fmt(m[k]):>10s}" for m in ms))
    return ms


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("collect").set_defaults(fn=collect)
    sub.add_parser("backfill").set_defaults(fn=backfill)
    p = sub.add_parser("mark")
    p.add_argument("label")
    p.set_defaults(fn=mark)
    sub.add_parser("changes").set_defaults(fn=changes)
    p = sub.add_parser("report")
    p.add_argument("--at", help="ISO time or a mark label (default: the last mark, else the last big description change)")
    p.add_argument("--days", type=int, default=3, help="whole days on each side (default 3)")
    p.add_argument("--trend-days", type=int, default=8, help="days before the change the history trend is fitted to")
    p.set_defaults(fn=report)
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
