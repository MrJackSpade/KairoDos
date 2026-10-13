"""Mirror GitHub issues and their comments into a Discord forum channel.

Each issue becomes a forum post; each comment, close and reopen becomes a
message in that post, sent under the GitHub user's name and avatar. Images and
videos uploaded to GitHub are attached to the message instead of linked.
An issue opened before the sync existed is mirrored in full the first time
anything happens to it. The Discord IDs are recorded next to this script so
later events can edit or delete the right messages:

  issues/<number>/thread.json           {"thread_id": ..., "message_id": ...}
  issues/<number>/comments/<id>.json    {"message_id": ...}

Every event writes or removes its own file, so concurrent runs never touch the
same path and their commits always rebase cleanly.
"""

import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

STATE = Path(__file__).resolve().parent / "issues"
WEBHOOK = os.environ["DISCORD_FORUM_WEBHOOK"]
REPO = os.environ["GITHUB_REPOSITORY"]
NO_MENTIONS = {"parse": []}
MESSAGE_LIMIT = 2000
# Discord's per-message limits for a webhook in a server without boosts.
ATTACHMENT_LIMIT = 10 * 1024 * 1024
ATTACHMENT_COUNT = 10

# Files uploaded to GitHub, as the issue editor writes them: an <img> tag, a Markdown
# image, or a bare link on its own line (how videos are inserted).
ASSET = (r"https://(?:github\.com/user-attachments/assets/[\w-]+"
         r"|github\.com/[\w.-]+/[\w.-]+/assets/\d+/[\w-]+"
         r"|user-images\.githubusercontent\.com/[^\s)\"'<>]+)")
ASSET_MARKUP = re.compile(
    rf"<img\b[^>]*?\bsrc=\"(?P<img>{ASSET})\"[^>]*>"
    rf"|!\[[^\]]*\]\((?P<md>{ASSET})\)"
    rf"|^[ \t]*(?P<bare>{ASSET})[ \t]*$",
    re.IGNORECASE | re.MULTILINE)


def clip(value, limit):
    return value if len(value) <= limit else value[: limit - 1] + "…"


def request(method, url, label, headers, data=None):
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


def discord(method, suffix, query, payload=None, files=()):
    """Send a webhook request. Files go as a multipart upload; a PATCH that lists the
    attachments replaces the message's old ones, so an edit without files clears them."""
    headers = {"User-Agent": f"{REPO} discord-sync"}
    data = None
    if payload is not None:
        if files or method == "PATCH":
            payload = {**payload, "attachments": [
                {"id": index, "filename": name} for index, (name, _) in enumerate(files)]}
        if files:
            boundary = uuid.uuid4().hex
            data = multipart(boundary, payload, files)
            headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
        else:
            data = json.dumps(payload).encode()
            headers["Content-Type"] = "application/json"
    # The label keeps the webhook token out of error messages.
    return request(method, WEBHOOK + suffix + query, f"Discord webhook{suffix}", headers, data)


def multipart(boundary, payload, files):
    parts = [(b'Content-Disposition: form-data; name="payload_json"\r\n'
              b"Content-Type: application/json", json.dumps(payload).encode())]
    for index, (name, data) in enumerate(files):
        quoted = name.replace('"', "")
        parts.append((f'Content-Disposition: form-data; name="files[{index}]"; filename="{quoted}"\r\n'
                      "Content-Type: application/octet-stream".encode(), data))
    body = b"".join(b"--" + boundary.encode() + b"\r\n" + head + b"\r\n\r\n" + data + b"\r\n"
                    for head, data in parts)
    return body + b"--" + boundary.encode() + b"--\r\n"


def github(path):
    headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
        "User-Agent": f"{REPO} discord-sync",
    }
    return request("GET", f"https://api.github.com/repos/{REPO}{path}", f"GitHub {path}", headers)


def download(url):
    """A GitHub upload as (filename, bytes), or None when it is over Discord's size limit.
    The asset link redirects to a short-lived signed download."""
    req = urllib.request.Request(url, headers={"User-Agent": f"{REPO} discord-sync"})
    try:
        with urllib.request.urlopen(req) as response:
            data = response.read(ATTACHMENT_LIMIT + 1)
            name = Path(urllib.parse.urlparse(response.geturl()).path).name
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"GET {url} -> {error.code}") from None
    return None if len(data) > ATTACHMENT_LIMIT else (name, data)


def with_footer(text, url):
    """Every message ends with a small link to its issue or comment; the angle brackets
    keep Discord from adding a preview card. Text too long for a message is cut to fit."""
    footer = f"-# [View on GitHub](<{url}>)"
    room = MESSAGE_LIMIT - len(footer) - 2
    if len(text) > room:
        text = text[: room - 1] + "…"
    return f"{text}\n\n{footer}" if text else footer


def render(body, url):
    """The message text and attachments for an issue or comment body. GitHub uploads become
    attachments; one over Discord's limits stays as a link."""
    files, attached = [], {}

    def attach(match):
        asset = match.group("img") or match.group("md") or match.group("bare")
        if asset not in attached:
            file = download(asset) if len(files) < ATTACHMENT_COUNT else None
            attached[asset] = file is not None
            if file:
                files.append(file)
        return "" if attached[asset] else f"<{asset}>"

    body = re.sub(r"[ \t]+\n", "\n", ASSET_MARKUP.sub(attach, body or ""))
    body = re.sub(r"\n{3,}", "\n\n", body).strip()
    return with_footer(body, url), files


def as_user(user):
    """Post under the GitHub user's own name and avatar instead of the webhook's."""
    return {"username": user["login"], "avatar_url": user["avatar_url"]} if user else {}


def new_message(user, content):
    return {"content": content, **as_user(user), "allowed_mentions": NO_MENTIONS}


def changed_message(content):
    # Clearing embeds also converts messages posted before the sync used plain text.
    return {"content": content, "embeds": [], "allowed_mentions": NO_MENTIONS}


def status(issue):
    if issue["state"] == "open":
        return "Reopened"
    reason = {"completed": "completed", "not_planned": "not planned",
              "duplicate": "a duplicate"}.get(issue.get("state_reason"))
    return f"Closed as {reason}" if reason else "Closed"


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
    content, files = render(issue["body"], issue["html_url"])
    payload = {
        "thread_name": clip(f"#{issue['number']} {issue['title']}", 100),
        **new_message(issue["user"], content),
    }
    message = discord("POST", "", "?wait=true", payload, files)
    write_new(path, {"thread_id": message["channel_id"], "message_id": message["id"]})
    print(f"Issue #{issue['number']} -> thread {message['channel_id']}")


def ensure_thread(issue):
    """Mirror an issue that predates the sync, with its comments and closure, the first time
    anything happens to it. Returns True when it did, since that covers the triggering event."""
    if thread_path(issue).exists():
        return False
    issue = github(f"/issues/{issue['number']}")
    open_issue(issue)
    for comment in paged(f"/issues/{issue['number']}/comments?sort=created"):
        add_comment(issue, comment)
    if issue["state"] == "closed":
        post_state(issue, issue.get("closed_by"))
    return True


def edit_issue(issue):
    if ensure_thread(issue):
        return
    thread = read(thread_path(issue))
    content, files = render(issue["body"], issue["html_url"])
    discord("PATCH", f"/messages/{thread['message_id']}", f"?thread_id={thread['thread_id']}",
            changed_message(content), files)
    print(f"Issue #{issue['number']} edited in thread {thread['thread_id']}")


def post_state(issue, actor):
    thread = read(thread_path(issue))
    payload = new_message(actor, with_footer(status(issue), issue["html_url"]))
    discord("POST", "", f"?wait=true&thread_id={thread['thread_id']}", payload)
    print(f"Issue #{issue['number']} {status(issue)} in thread {thread['thread_id']}")


def change_state(issue, actor):
    if ensure_thread(issue):
        return
    post_state(issue, actor)


def add_comment(issue, comment):
    thread = read(thread_path(issue))
    path = comment_path(issue, comment)
    if path.exists():
        raise RuntimeError(f"Comment {comment['id']} is already in Discord: {path}")
    content, files = render(comment["body"], comment["html_url"])
    message = discord("POST", "", f"?wait=true&thread_id={thread['thread_id']}",
                      new_message(comment["user"], content), files)
    write_new(path, {"message_id": message["id"]})
    print(f"Comment {comment['id']} -> message {message['id']}")


def edit_comment(issue, comment):
    thread = read(thread_path(issue))
    message_id = read(comment_path(issue, comment))["message_id"]
    content, files = render(comment["body"], comment["html_url"])
    discord("PATCH", f"/messages/{message_id}", f"?thread_id={thread['thread_id']}",
            changed_message(content), files)
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
        if "pull_request" not in issue:
            ensure_thread(issue)


def refresh(number):
    """Mirror one issue, or rewrite its mirrored messages from GitHub's current text."""
    issue = github(f"/issues/{number}")
    if ensure_thread(issue):
        return
    edit_issue(issue)
    for comment in paged(f"/issues/{number}/comments?sort=created"):
        if comment_path(issue, comment).exists():
            edit_comment(issue, comment)


def on_comment(handler):
    return lambda event: ensure_thread(event["issue"]) or handler(event["issue"], event["comment"])


HANDLERS = {
    ("issues", "opened"): lambda event: open_issue(event["issue"]),
    ("issues", "edited"): lambda event: edit_issue(event["issue"]),
    ("issues", "closed"): lambda event: change_state(event["issue"], event["sender"]),
    ("issues", "reopened"): lambda event: change_state(event["issue"], event["sender"]),
    ("issue_comment", "created"): on_comment(add_comment),
    ("issue_comment", "edited"): on_comment(edit_comment),
    ("issue_comment", "deleted"): on_comment(delete_comment),
}


def main():
    name = os.environ["GITHUB_EVENT_NAME"]
    if name == "workflow_dispatch":
        number = os.environ["SYNC_ISSUE"]
        if number:
            refresh(int(number))
        else:
            backfill()
        return
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    HANDLERS[(name, event["action"])](event)


if __name__ == "__main__":
    main()
