"""Mirror GitHub issues and their comments into a Discord forum channel.

Each issue becomes a forum post; each comment becomes a message in that post.
The Discord IDs are recorded next to this script so later events can edit or
delete the right messages:

  issues/<number>/thread.json           {"thread_id": ..., "message_id": ...}
  issues/<number>/comments/<id>.json    {"message_id": ...}

Every event writes or removes its own file, so concurrent runs never touch the
same path and their commits always rebase cleanly.
"""

import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

STATE = Path(__file__).resolve().parent / "issues"
WEBHOOK = os.environ["DISCORD_FORUM_WEBHOOK"]
REPO = os.environ["GITHUB_REPOSITORY"]
NO_MENTIONS = {"parse": []}


def clip(text, limit):
    return text if len(text) <= limit else text[: limit - 1] + "…"


def request(method, url, label, headers, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    while True:
        try:
            with urllib.request.urlopen(req) as response:
                body = response.read()
                return json.loads(body) if body else None
        except urllib.error.HTTPError as error:
            detail = error.read().decode()
            # Discord's rate limit says exactly how long to wait before resending.
            if error.code == 429 and label.startswith("Discord"):
                time.sleep(json.loads(detail)["retry_after"])
                continue
            raise RuntimeError(f"{method} {label} -> {error.code}: {detail}") from None


def discord(method, suffix, query, payload=None):
    headers = {"Content-Type": "application/json", "User-Agent": f"{REPO} discord-sync"}
    # The label keeps the webhook token out of error messages.
    return request(method, WEBHOOK + suffix + query, f"Discord webhook{suffix}", headers, payload)


def github(path):
    headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
        "User-Agent": f"{REPO} discord-sync",
    }
    return request("GET", f"https://api.github.com/repos/{REPO}{path}", f"GitHub {path}", headers)


def author(user):
    return {"name": user["login"], "url": user["html_url"], "icon_url": user["avatar_url"]}


def issue_embed(issue):
    embed = {
        "title": clip(f"#{issue['number']}: {issue['title']}", 256),
        "url": issue["html_url"],
        "author": author(issue["user"]),
        "timestamp": issue["created_at"],
    }
    if issue["body"]:
        embed["description"] = clip(issue["body"], 4096)
    labels = ", ".join(label["name"] for label in issue["labels"])
    if labels:
        embed["fields"] = [{"name": "Labels", "value": clip(labels, 1024)}]
    return embed


def comment_embed(issue, comment):
    embed = {
        "title": f"Comment on #{issue['number']}",
        "url": comment["html_url"],
        "author": author(comment["user"]),
        "timestamp": comment["created_at"],
    }
    if comment["body"]:
        embed["description"] = clip(comment["body"], 4096)
    return embed


def thread_path(issue):
    return STATE / str(issue["number"]) / "thread.json"


def comment_path(issue, comment):
    return STATE / str(issue["number"]) / "comments" / f"{comment['id']}.json"


def read(path):
    return json.loads(path.read_text())


def write_new(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x") as file:
        json.dump(data, file, indent=2)
        file.write("\n")


def open_issue(issue):
    path = thread_path(issue)
    if path.exists():
        raise RuntimeError(f"Issue #{issue['number']} already has a Discord thread: {path}")
    payload = {
        "thread_name": clip(f"#{issue['number']} {issue['title']}", 100),
        "embeds": [issue_embed(issue)],
        "allowed_mentions": NO_MENTIONS,
    }
    message = discord("POST", "", "?wait=true", payload)
    write_new(path, {"thread_id": message["channel_id"], "message_id": message["id"]})
    print(f"Issue #{issue['number']} -> thread {message['channel_id']}")


def edit_issue(issue):
    thread = read(thread_path(issue))
    payload = {"embeds": [issue_embed(issue)], "allowed_mentions": NO_MENTIONS}
    discord("PATCH", f"/messages/{thread['message_id']}", f"?thread_id={thread['thread_id']}", payload)
    print(f"Issue #{issue['number']} edited in thread {thread['thread_id']}")


def add_comment(issue, comment):
    thread = read(thread_path(issue))
    path = comment_path(issue, comment)
    if path.exists():
        raise RuntimeError(f"Comment {comment['id']} is already in Discord: {path}")
    payload = {"embeds": [comment_embed(issue, comment)], "allowed_mentions": NO_MENTIONS}
    message = discord("POST", "", f"?wait=true&thread_id={thread['thread_id']}", payload)
    write_new(path, {"message_id": message["id"]})
    print(f"Comment {comment['id']} -> message {message['id']}")


def edit_comment(issue, comment):
    thread = read(thread_path(issue))
    message_id = read(comment_path(issue, comment))["message_id"]
    payload = {"embeds": [comment_embed(issue, comment)], "allowed_mentions": NO_MENTIONS}
    discord("PATCH", f"/messages/{message_id}", f"?thread_id={thread['thread_id']}", payload)
    print(f"Comment {comment['id']} edited in message {message_id}")


def delete_comment(issue, comment):
    thread = read(thread_path(issue))
    path = comment_path(issue, comment)
    message_id = read(path)["message_id"]
    discord("DELETE", f"/messages/{message_id}", f"?thread_id={thread['thread_id']}")
    path.unlink()
    print(f"Comment {comment['id']} deleted message {message_id}")


def paged(path):
    page = 1
    while True:
        items = github(f"{path}&per_page=100&page={page}")
        if not items:
            return
        yield from items
        page += 1


def backfill():
    """Create threads for every issue that does not have one yet, oldest first."""
    for issue in paged("/issues?state=all&sort=created&direction=asc"):
        if "pull_request" in issue or thread_path(issue).exists():
            continue
        open_issue(issue)
        for comment in paged(f"/issues/{issue['number']}/comments?sort=created"):
            add_comment(issue, comment)


HANDLERS = {
    ("issues", "opened"): lambda event: open_issue(event["issue"]),
    ("issues", "edited"): lambda event: edit_issue(event["issue"]),
    ("issue_comment", "created"): lambda event: add_comment(event["issue"], event["comment"]),
    ("issue_comment", "edited"): lambda event: edit_comment(event["issue"], event["comment"]),
    ("issue_comment", "deleted"): lambda event: delete_comment(event["issue"], event["comment"]),
}


def main():
    name = os.environ["GITHUB_EVENT_NAME"]
    if name == "workflow_dispatch":
        backfill()
        return
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    HANDLERS[(name, event["action"])](event)


if __name__ == "__main__":
    main()
