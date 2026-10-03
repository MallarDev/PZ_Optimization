#!/usr/bin/env python3
"""Announce a published release in the project's Discord server (https://discord.gg/WNeQqYZ4T).

    scripts/discord-announce.py --tag b42.21-20261003-2207-8d97af98            # post it
    scripts/discord-announce.py --tag <tag> --dry-run                           # print the payload only
    scripts/discord-announce.py --tag <tag> --headline "Zombies attack cars again"

Every release is announced (maintainer, 2026-10-04). scripts/workshop.sh --tag <tag> --upload runs this after
a successful Workshop upload, so the post links both the GitHub release and the Workshop page.

The post goes through a channel webhook (Discord: channel > Edit Channel > Integrations > Webhooks > New
Webhook > Copy Webhook URL). The URL is a write credential and the repo is public, so it is never committed:
$PZOPT_DISCORD_WEBHOOK, else ~/.config/pzopt/discord-webhook (one line). Tags already posted are listed in
~/.config/pzopt/discord-announced.txt; a second post of the same tag needs --force.

Headline: --headline (the user-facing change in a few words), else the release title. Body: the release notes' middle part (what
release.sh --notes added), without the boilerplate install / sha256 sentences.
"""
import argparse, json, os, re, subprocess, sys, urllib.error, urllib.request
from pathlib import Path

CONF = Path.home() / ".config/pzopt"
WORKSHOP = "https://steamcommunity.com/sharedfiles/filedetails/?id=3805285544"


def webhook():
    url = os.environ.get("PZOPT_DISCORD_WEBHOOK", "").strip()
    if not url and (CONF / "discord-webhook").exists():
        url = (CONF / "discord-webhook").read_text().strip()
    return url


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--headline")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args()

    rel = json.loads(subprocess.check_output(
        ["gh", "release", "view", a.tag, "--json", "name,url,body,publishedAt"], text=True))
    headline = a.headline or rel["name"]
    body = rel["body"]
    # release.sh writes "Prebuilt ... (Build 42.x). <extra notes> Install with ... sha256 <hex>"
    m = re.search(r"^Prebuilt .*?\)\.\s*(.*?)\s*Install with ", body, re.S)
    notes = m.group(1).strip() if m else ""
    if len(notes) > 3800:
        notes = notes[:3800].rsplit(" ", 1)[0] + " …"

    desc = (notes + "\n\n" if notes else "") + (
        f"**Install:** Steam Workshop ([subscribe]({WORKSHOP})) or `install.ps1` / `install.sh` "
        f"from the [GitHub release]({rel['url']}).")
    payload = {
        "username": "PZ_Optimization",
        "content": f"**New release: {headline}**",
        "embeds": [{
            "title": rel["name"],
            "url": rel["url"],
            "description": desc,
            "color": 0x4C8C2B,
            "footer": {"text": a.tag},
            "timestamp": rel["publishedAt"],
        }],
        "allowed_mentions": {"parse": []},
    }
    if a.dry_run:
        print(json.dumps(payload, indent=2, ensure_ascii=False))
        return 0

    sent = CONF / "discord-announced.txt"
    if not a.force and sent.exists() and a.tag in sent.read_text().split():
        print(f"discord: {a.tag} was already announced (--force to post again)")
        return 0
    url = webhook()
    if not re.match(r"^https://(?:\w+\.)?discord(?:app)?\.com/api/webhooks/", url):
        print("discord: no webhook URL (set PZOPT_DISCORD_WEBHOOK or ~/.config/pzopt/discord-webhook); "
              "an invite link (discord.gg/...) cannot post", file=sys.stderr)
        return 2
    req = urllib.request.Request(url + "?wait=true", data=json.dumps(payload).encode(), method="POST", headers={
        "Content-Type": "application/json",
        # Discord's edge refuses the default Python-urllib agent
        "User-Agent": "PZ_Optimization-release (https://github.com/xD3I/PZ_Optimization, 1.0)"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            msg = json.load(r)
    except urllib.error.HTTPError as e:
        print(f"discord: post FAILED: HTTP {e.code} {e.read().decode(errors='replace')[:300]}", file=sys.stderr)
        return 3
    CONF.mkdir(parents=True, exist_ok=True)
    with sent.open("a") as f:
        f.write(a.tag + "\n")
    print(f"discord: announced {a.tag} (message {msg.get('id')} in channel {msg.get('channel_id')})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
