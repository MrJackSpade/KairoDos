"""Announce a GitHub release in a Discord channel through a webhook."""

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

tag = sys.argv[1]
repo = os.environ["GITHUB_REPOSITORY"]
fields = "name,tagName,url,body,isPrerelease,publishedAt,author,assets"
release = json.loads(subprocess.check_output(["gh", "release", "view", tag, "-R", repo, "--json", fields]))


def clip(text, limit):
    return text if len(text) <= limit else text[: limit - 1] + "…"


downloads = "\n".join(f"[{asset['name']}]({asset['url']})" for asset in release["assets"])
embed = {
    "title": clip(release["name"] + (" (pre-release)" if release["isPrerelease"] else ""), 256),
    "url": release["url"],
    "color": 0x5865F2,
    "timestamp": release["publishedAt"],
    "author": {"name": release["author"]["login"], "url": f"https://github.com/{release['author']['login']}"},
}
if release["body"]:
    embed["description"] = clip(release["body"], 4096)
if downloads:
    embed["fields"] = [{"name": "Downloads", "value": clip(downloads, 1024)}]

payload = {"embeds": [embed], "allowed_mentions": {"parse": []}}
request = urllib.request.Request(
    os.environ["DISCORD_RELEASE_WEBHOOK"] + "?wait=true",
    data=json.dumps(payload).encode(),
    headers={"Content-Type": "application/json", "User-Agent": f"{repo} discord-release"},
)
try:
    with urllib.request.urlopen(request) as response:
        message = json.load(response)
except urllib.error.HTTPError as error:
    raise RuntimeError(f"Discord webhook -> {error.code}: {error.read().decode()}") from None
print(f"Announced {release['tagName']} as Discord message {message['id']}")
