# Per-Project Session Persistence

## Problem

Session state is stored in a single global file (`{configDir}/codeplangui/session.json`). All projects share the same conversation, so opening a new project shows the previous project's session.

## Solution

Store sessions under a project-specific path keyed by project basePath hash.

### Storage Path

```
Before: {configDir}/codeplangui/session.json
After:  {configDir}/codeplangui/sessions/{projectHash}/session.json
```

`projectHash` = SHA-256 of project `basePath`, truncated to 16 hex chars (avoids filesystem issues with special characters in paths).

### Changes

1. **`SessionStore.kt`** — Accept `projectId` parameter, resolve path to `sessions/{projectId}/session.json`. Create parent dirs on save.
2. **`ChatService.kt`** — Derive `projectId` from `project.basePath`, pass to `SessionStore`. Each project instance gets its own session.
3. **No frontend changes** — Frontend communicates via bridge, which is per-project.

### Data Flow

```
Project A opens → ChatService(projectA) → SessionStore.load("abc123") → loads project A session
Project B opens → ChatService(projectB) → SessionStore.load("def456") → loads project B session (or creates new)
```

### Expiration Policy

None for v1. Session files are small JSON (typically < 100KB). Can add time-based or count-based expiration later if needed.

### Testing

- Unit tests for `SessionStore` with project-specific paths
- Unit tests for `ChatService` session isolation between projects
- Verify session restore after IDE restart
- Verify multiple projects maintain independent sessions
