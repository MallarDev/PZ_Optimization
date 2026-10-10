#!/usr/bin/env python3
"""One snapshot of the Steam Workshop item's public numbers into workshop_stats (local DB + the public dashboard's DB).

  harness/grafana/workshop_stats.py            # fetch + store, print the row
  harness/grafana/workshop_stats.py --dry-run  # fetch + print only
  harness/grafana/workshop_stats.py --mark "proposal b live" [--at <ISO time>] [--kind restyle|mark]
  harness/grafana/workshop_stats.py --report [--at <ISO time or mark label>] [--days 3] [--trend-days 8]

Subscribers, favorites and unique visitors come from the keyless Web API (ISteamRemoteStorage/GetPublishedFileDetails);
the star rating (Steam's rounded 0-5 stars image), the rating count, the comment count and the award reactions only from
the public item page, which Steam rate-limits per IP (HTTP 429): those columns stay NULL then and the dashboard shows the
newest snapshot that has them. No Steamworks session: a process holding app 108600 would look like a running game to
Steam and could block a harness launch. The follower (ingest.py --follow) calls snapshot() every 30 min.

Since 2026-10-10 (the description restyle) each snapshot also stores what the PZ Workshop page dashboard reads (tables in
schema.sql; SQL in workshop_sql.py): every GitHub release's downloads (workshop_releases), every comment's time
(workshop_comments, the comment render endpoint, newest first until the newest stored one), GitHub's daily views
(workshop_github_views, kept past the API's 14 days), stars / Discord members / steamcommunity.com referrals
(workshop_extra), and each description version (workshop_descriptions): a new one adds a `description` row to
workshop_page_changes, with its similarity to the previous text (a release rewrites the build line and sha256: ignored).
`--mark` adds a row by hand (`restyle` = a page redesign, the before / after table's choices; run it right after the
upload). GitHub's traffic numbers are owner-only data and the public DB answers any viewer's SQL, so views and referrals
stay in the local DB. Installs are invisible: the helper window and the by-hand copy install from the Workshop item's
own files, never from GitHub, so the GitHub installer downloads count only the page's one-line fallback.
"""
import argparse
import base64
import datetime
import difflib
import hashlib
import json
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

from ingest import psql_query, psql_run, remote
from workshop_sql import before_after

ITEM = 3805285544
API = "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/"
PAGE = f"https://steamcommunity.com/sharedfiles/filedetails/?id={ITEM}"
UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) pzopt-dashboard"}
CREATOR = "76561198030422376"
REPO = "xD3I/PZ_Optimization"
DISCORD = "WNeQqYZ4T"


def fetch_api():
    body = urllib.parse.urlencode({"itemcount": 1, "publishedfileids[0]": ITEM}).encode()
    d = json.load(urllib.request.urlopen(urllib.request.Request(API, body, UA), timeout=20))["response"]["publishedfiledetails"][0]
    if d.get("result") != 1:
        raise RuntimeError(f"GetPublishedFileDetails result {d.get('result')}")
    return {"subscribers": d["subscriptions"], "lifetime_subscribers": d["lifetime_subscriptions"], "favorites": d["favorited"],
            "lifetime_favorites": d["lifetime_favorited"], "visitors": d["views"], "description": d.get("description", "")}


def fetch_page():
    html = urllib.request.urlopen(urllib.request.Request(PAGE, headers=UA), timeout=20).read().decode(errors="replace")
    n = lambda pat: (m := re.search(pat, html)) and int(m.group(1).replace(",", ""))  # noqa: E731
    return {"stars": n(r"sharedfiles/(\d)-star_large\.png"),  # "not-yet_large.png" until Steam has enough ratings
            "ratings": n(r'class="numRatings">([\d,]+) rating'),
            "comments": n(rf'_{ITEM}_totalcount">([\d,]+)<'),
            "awards": sum(int(x) for x in re.findall(r'data-reactioncount="(\d+)"', html)) or None}


def sql_str(v):
    return "NULL" if v is None else "'" + str(v).replace("'", "''") + "'"


def store(script, what, public=True):
    psql_run(script)
    if public:
        remote().apply(script, what)


def gh(path):
    p = subprocess.run(["gh", "api", path], capture_output=True, text=True, timeout=60)
    if p.returncode:
        raise RuntimeError(p.stderr.strip()[:300])
    return json.loads(p.stdout)


def normalize(desc):
    """The description without what every release rewrites (commit, revision, sha256, file count, build number)."""
    d = re.sub(r"\b[0-9a-f]{8,64}\b", "#", desc)
    d = re.sub(r"\b\d+ files\b", "# files", d)
    return re.sub(r"Build 4\d(\.\d+)*", "Build #", d)


def description(desc):
    h = hashlib.sha256(normalize(desc).encode()).hexdigest()[:16]
    known = psql_query("SELECT hash, translate(encode(convert_to(body, 'UTF8'), 'base64'), E'\\n', '') "
                       "FROM workshop_descriptions ORDER BY first_seen DESC LIMIT 1")
    if known and known[0][0] == h or psql_query(f"SELECT 1 FROM workshop_descriptions WHERE hash = '{h}'"):
        return
    script = (f"INSERT INTO workshop_descriptions (hash, first_seen, chars, body) VALUES ('{h}', now(), {len(desc)}, "
              f"{sql_str(desc)}) ON CONFLICT DO NOTHING;\n")
    if known:  # the first version stored is the baseline, not a change
        sim = difflib.SequenceMatcher(None, normalize(base64.b64decode(known[0][1]).decode()), normalize(desc), autojunk=False).ratio()
        script += ("INSERT INTO workshop_page_changes (t, kind, label) VALUES (now(), 'description', "
                   f"{sql_str(f'description changed, similarity {sim:.2f} ({len(desc):,} chars)')}) ON CONFLICT DO NOTHING;\n")
    store(script, "workshop description")


def releases():
    rows, page = [], 1
    while True:
        batch = gh(f"repos/{REPO}/releases?per_page=100&page={page}")
        rows += batch
        if len(batch) < 100:
            break
        page += 1
    vals = []
    for r in rows:
        if r["draft"] or r["prerelease"] or not r.get("published_at"):
            continue
        d = {"installer": 0, "zip": 0, "uninstaller": 0}
        for a in r["assets"]:
            n = a["name"]
            k = "uninstaller" if n.startswith("uninstall") else "installer" if n.startswith("install") else \
                "zip" if n.endswith(".zip") else None
            if k:
                d[k] += a["download_count"]
        vals.append(f"({sql_str(r['tag_name'])}, '{r['published_at']}', {d['installer']}, {d['zip']}, {d['uninstaller']})")
    if vals:
        store("INSERT INTO workshop_releases (tag, t, installer, zip, uninstaller) VALUES " + ", ".join(vals)
              + " ON CONFLICT (tag) DO UPDATE SET t = EXCLUDED.t, installer = EXCLUDED.installer, zip = EXCLUDED.zip, "
                "uninstaller = EXCLUDED.uninstaller;\n", "workshop releases")
    return len(vals)


def comments():
    """New comments since the newest stored one (all of them the first time), counted per second."""
    newest = psql_query("SELECT coalesce(extract(epoch FROM max(t))::bigint, 0) FROM workshop_comments")[0][0]
    newest, start, seen = int(newest), 0, {}
    while True:
        req = urllib.request.Request(f"https://steamcommunity.com/comment/PublishedFile_Public/render/{CREATOR}/{ITEM}/"
                                     f"?start={start}&count=100", headers=UA)
        d = json.load(urllib.request.urlopen(req, timeout=20))
        ts = [int(x) for x in re.findall(r'data-timestamp="(\d+)"', d.get("comments_html", ""))]
        for t in ts:
            if t > newest:
                seen[t] = seen.get(t, 0) + 1
        start += 100
        if not ts or min(ts) <= newest or start >= d.get("total_count", 0):
            break
        time.sleep(1)  # the community site rate-limits per IP
    if seen:
        store("INSERT INTO workshop_comments (t, n) VALUES "
              + ", ".join(f"(to_timestamp({t}), {n})" for t, n in sorted(seen.items())) + " ON CONFLICT DO NOTHING;\n",
              "workshop comments")
    return sum(seen.values())


def github_private():
    """Owner-only GitHub traffic: local DB only (the public DB answers anonymous SQL)."""
    views = gh(f"repos/{REPO}/traffic/views")["views"]
    refs = {r["referrer"]: r for r in gh(f"repos/{REPO}/traffic/popular/referrers")}
    script = ""
    if views:
        script += ("INSERT INTO workshop_github_views (day, views, uniques) VALUES "
                   + ", ".join(f"('{v['timestamp'][:10]}', {v['count']}, {v['uniques']})" for v in views)
                   + " ON CONFLICT (day) DO UPDATE SET views = EXCLUDED.views, uniques = EXCLUDED.uniques;\n")
    steam = refs.get("steamcommunity.com", {})
    return script, steam.get("count"), steam.get("uniques")


def extra(private_script, refs, ref_uniques):
    stars = gh(f"repos/{REPO}")["stargazers_count"]
    dc = json.load(urllib.request.urlopen(urllib.request.Request(
        f"https://discord.com/api/v9/invites/{DISCORD}?with_counts=true", headers=UA), timeout=20))
    cols = f"github_stars, discord_members, discord_online"
    vals = f"{int(stars)}, {dc.get('approximate_member_count') or 'NULL'}, {dc.get('approximate_presence_count') or 'NULL'}"
    now = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")
    store(f"INSERT INTO workshop_extra (t, {cols}) VALUES ('{now}', {vals}) ON CONFLICT DO NOTHING;\n", "workshop extra")
    store(private_script + (f"UPDATE workshop_extra SET steam_referrals = {refs}, steam_referral_uniques = {ref_uniques} "
                            f"WHERE t = '{now}';\n" if refs is not None else ""), "", public=False)


def history(desc):
    """Everything besides the Steam counters; each part on its own, so one source down costs only its own rows."""
    done = []
    for name, fn in (("description", lambda: description(desc)), ("releases", releases), ("comments", comments),
                     ("github + discord", lambda: extra(*github_private()))):
        try:
            r = fn()
            done.append(f"{name}{'' if r is None else f' {r}'}")
        except Exception as e:
            print(f"workshop: {name} skipped ({str(e)[:200]})", file=sys.stderr, flush=True)
    return done


def snapshot(dry_run=False):
    row = {"item": ITEM, **fetch_api()}
    desc = row.pop("description")
    try:
        row.update(fetch_page())
    except Exception as e:  # 429 and friends: the counts still go in
        print(f"workshop: page skipped ({e})", file=sys.stderr, flush=True)
    if not dry_run:
        cols = list(row)
        script = (f"INSERT INTO workshop_stats (t, {', '.join(cols)}) VALUES (now(), "
                  + ", ".join("NULL" if row[c] is None else str(int(row[c])) for c in cols) + ") ON CONFLICT DO NOTHING;\n")
        psql_run(script)
        remote().apply(script, "workshop stats")
        row["stored"] = history(desc)
    return row


def mark(label, at=None, kind="mark"):
    t = sql_str(at) + "::timestamptz" if at else "now()"
    store(f"INSERT INTO workshop_page_changes (t, kind, label) VALUES ({t}, {sql_str(kind)}, {sql_str(label)}) "
          "ON CONFLICT (t) DO UPDATE SET kind = EXCLUDED.kind, label = EXCLUDED.label;\n", "workshop mark")


def report(at, days, trend):
    if at and not re.match(r"\d{4}-\d\d-\d\d", at):
        rows = psql_query(f"SELECT t FROM workshop_page_changes WHERE label = {sql_str(at)} ORDER BY t DESC LIMIT 1")
        if not rows:
            sys.exit(f"no page change labelled {at!r}")
        at = rows[0][0]
    if not at:
        rows = psql_query("SELECT t, label FROM workshop_page_changes WHERE kind IN ('restyle', 'mark') ORDER BY t DESC LIMIT 1")
        if not rows:
            sys.exit("no restyle or mark recorded: pass --at")
        at = rows[0][0]
        print(f"page change: {rows[0][1]}")
    print(f"change at {at}; {days} whole UTC days each side (the change day left out), trend fitted to {trend} days before")
    p = subprocess.run(["psql", "-X", "-P", "pager=off", "-P", "numericlocale=off", "-c",
                        "SELECT metric, round(before::numeric, 1) AS before, round(\"before low\"::numeric, 1) AS low, "
                        "round(\"before high\"::numeric, 1) AS high, round(after::numeric, 1) AS after, "
                        "round(\"after vs before %\"::numeric) AS \"vs before %\", round(\"trend expected\"::numeric, 1) AS trend, "
                        "round(\"after vs trend %\"::numeric) AS \"vs trend %\", round(\"trend before %/day\"::numeric) AS \"trend %/day\", "
                        "\"days after\" FROM (" + before_after(sql_str(at) + "::timestamptz", days, trend) + ") r"],
                       text=True, capture_output=True)
    print(p.stdout or p.stderr)


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--dry-run", action="store_true", help="fetch and print, store nothing")
    ap.add_argument("--mark", metavar="LABEL", help="record a page change (default now, see --at) instead of a snapshot")
    ap.add_argument("--kind", default="mark", choices=["restyle", "mark"], help="with --mark: restyle = a page redesign")
    ap.add_argument("--report", action="store_true", help="the before / after table of a page change")
    ap.add_argument("--at", help="--mark: when (ISO time); --report: the change (ISO time or a mark label; default the newest)")
    ap.add_argument("--days", type=int, default=3)
    ap.add_argument("--trend-days", type=int, default=8)
    a = ap.parse_args(argv)
    if a.mark:
        mark(a.mark, a.at, a.kind)
    elif a.report:
        report(a.at, a.days, a.trend_days)
    else:
        print(json.dumps(snapshot(a.dry_run)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
