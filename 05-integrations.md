# Integration plan

## Confirmed environment

Jira Cloud: https://your-site.atlassian.net
GitHub: token-based access is acceptable to the owner.
Actual credentials, repositories, organizations, and permissions have not been inspected.
Do not assume every organizational repository or field is accessible.

## Jira v1

Read-only capabilities:
- List issues assigned to the authenticated user.
- Search other accessible tickets.
- Link existing ticket to one or more Topics.
- Create a Topic from a ticket.
- Detailed title, formatted description, status, assignee, priority, fields, comments, related issues/subtasks, attachment links.
- Changelog can be loaded on demand where useful; exhaustive history is not required on first paint.
- Open the source ticket in Jira.

Cloud REST API v3 provides issue and related endpoints.
Examples to verify against current official docs during implementation:
- GET /rest/api/3/issue/{issueIdOrKey}
- GET /rest/api/3/issue/{issueIdOrKey}/comment
- GET /rest/api/3/issue/{issueIdOrKey}/changelog
- GET /rest/api/3/field

Search endpoint/JQL semantics should be checked when implementing; do not reuse deprecated examples blindly.
Fetch paginated comments/history to the requested completeness.
Custom field IDs must be resolved from metadata, not hardcoded to another organization's schema.
Jira's API entity properties are different from visible custom fields; do not confuse them.

### Rich descriptions

Jira Cloud v3 uses Atlassian Document Format for descriptions/comments.
Build or adopt a renderer for supported nodes: paragraphs, headings, lists, links, code, tables, mentions, and accessible media fallbacks as available.
Renderer scope must be validated using representative permitted content.
Do not assume one plain-text string reproduces Jira.
Plugin panels may need separate APIs and are not automatically supported.
Sanitize rendered content; do not inject untrusted HTML blindly.

### Authentication

For a private proof of concept, user email + API token may be an option.
OAuth 2.0 is another option.
Final method depends on organizational settings, token scopes, and browser restrictions.
Validate CORS and refresh/expiry behavior before deciding the integration-service boundary.
Do not put privileged credentials into a public client build.

### Caching

API is authoritative.
Display cached details immediately; revalidate online.
No persistent Firestore mirror of descriptions/comments.
Every device maintains its own downloaded cache.
Handle pagination, permission loss, 401/403, 429/backoff, and partial data explicitly.

## GitHub v1

Views:
- My PRs.
- Review Requested.
- PR detail and source link.
- CI/check status and review state.
- Link to Topic or create Topic.

Token permissions, repository selection, organization approval, and SSO can limit access.
Token possession does not imply access to all information.
Current-user identity must be discovered, not hardcoded.
Whether team-based review requests count alongside direct requests is open.
Drafts, closed PR inclusion, default filters, and refresh cadence remain open.

Proposed data policy: API authority + device-local detailed cache; cloud stores minimal references and Topic associations.
Do not store diffs or repository contents just to display PR metadata.
Full diff review tooling is not a v1 requirement.

## External writes: explicitly deferred

Remember these features for later:
- Jira comments and status transitions.
- GitHub comments/reviews and other write actions if requested.
No speculative write endpoints, hidden background mutations, or merge controls in v1.
Read-only integrations simplify offline handling: no remote mutation queue yet.

## AI and Claude Code: deferred

Desired future capabilities:
- AI usage overview.
- Claude Code session visibility and optional Topic associations.
- Continue/manage sessions remotely where supported.

Do not invent an embeddable Claude Remote Control API.
Official remote continuation links and local telemetry may be a first integration path.
Local sessions depend on their host machine and supported account configuration.
Subscription remaining quota is not the same as token/cost telemetry.
Validate capabilities at that future stage.

## Reminders

Definitions and in-app visibility are in scope.
Delivery while the app is closed is deferred.
Do not promise that a web timer alone schedules reliable closed-app notifications.

## Official references for implementation-time validation

These are reference starting points, not a claim of compatibility testing:
- https://developer.atlassian.com/cloud/jira/platform/rest/v3/
- https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issues/
- https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issue-comments/
- https://developer.atlassian.com/cloud/jira/platform/basic-auth-for-rest-apis/
- https://developer.atlassian.com/cloud/jira/platform/oauth-2-3lo-apps/
- https://docs.github.com/en/rest/authentication/authenticating-to-the-rest-api
- https://firebase.google.com/docs/firestore/manage-data/enable-offline
- https://kotlinlang.org/docs/web-overview.html
- https://github.com/JetBrains/compose-multiplatform
- https://github.com/GitLiveApp/firebase-kotlin-sdk
- https://code.claude.com/docs/en/remote-control
- https://code.claude.com/docs/en/monitoring-usage

