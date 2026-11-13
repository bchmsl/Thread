from pathlib import Path

directory = Path(__file__).resolve().parent
template = (directory / "template.html").read_text()
script = (directory / "app.js").read_text()
entries = {
    "index": "today",
    "today": "today",
    "topics": "topics",
    "topic-details": "topic/qr",
    "notes": "notes",
    "inbox": "inbox",
    "task-details": "task/t2",
    "jira": "jira",
    "jira-details": "jira/PAY-142",
    "pull-requests": "prs",
    "pr-details": "prs/248",
    "settings": "settings",
    "trash": "trash",
    "sign-in": "welcome",
}
for filename, route in entries.items():
    page = template.replace("<body>", f'<body data-start="{route}">')
    page = page.replace('<script src="app.js"></script>', f"<script>\n{script}</script>")
    (directory / (filename + ".html")).write_text(page)
