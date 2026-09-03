# DevPilot — Known Issues

Compiled from a full codebase sweep on 2026-09-03 (backend: Spring Boot/Java; frontend: Next.js/React). Originally 42 issues across 7 subsystems; updated 2026-09-03 after four fix passes — live bug-fixing while getting indexing/chat working end-to-end, a systematic pass through the rest of the report, a dedicated global error-handling audit and fix, then an efficiency/resilience pass (incremental re-indexing, Groq model fallback, proactive rate limiting). 34 of the original 42 are resolved (#36 folded into the error-handling pass), plus a separate 4-area error-handling audit (24 more gaps found and fixed) and three new resilience features on top. Backend recompiled and boot-tested against the live dev DB after every change, including live curl checks of the new error and rate-limit paths; frontend lint + `tsc --noEmit` clean, dev server smoke-tested after every batch. 8 of the original 42 remain, all either requiring a manual action only the project owner can take, or deliberately deferred as separate, larger efforts.

Legend: 🔴 Critical · 🟠 High · 🟡 Medium · ⚪ Low

---

## ✅ Resolved

### Global error-handling audit & fix (2026-09-03, third pass)

A dedicated 4-way audit (backend controllers/exception handling, backend services/external-API error translation, frontend API client/query error handling, frontend page/component error boundaries) found 24 concrete gaps. All fixed:

**Backend — exceptions that used to bypass `GlobalExceptionHandler` entirely, or hit it but got the wrong status/message:**
- The 401 entry point (`SecurityConfig`) — the ONE 401 path a logged-out user actually hits — bypassed the app's error contract completely and fell through to Spring Boot's default `/error` body (inconsistent shape, empty message). Now returns the same `{status,error,message,timestamp}` JSON as everything else, verified live via curl.
- Unmapped routes returned the container default instead of a clean 404. Added `spring.mvc.throw-exception-if-no-handler-found=true` + a `NoHandlerFoundException` handler — verified live via curl.
- Bad path params (`/api/repos/not-a-uuid`), missing required query params, malformed JSON bodies, and wrong HTTP methods all fell through to the generic handler and came back as a misleading HTTP 500 "unexpected error" instead of a 400/405. Added dedicated handlers for `MissingServletRequestParameterException`, `MethodArgumentTypeMismatchException`, `HttpMessageNotReadableException`, and `HttpRequestMethodNotSupportedException`.

**Backend — external service (GitHub/Gemini) errors leaking raw provider text:**
- `GithubApiClient` and `GeminiEmbeddingModel` had zero error handling — any 401/403/404/5xx surfaced as a raw `HttpClientErrorException` whose message embeds the provider's literal JSON error body. Worse, `IndexingService.markFailed` stored that raw text directly into `Repository.errorMessage`, which the frontend displays verbatim. Introduced `ExternalServiceException` (a `UserFacingException` subtype carrying the service name + upstream status) with centralized translation in both clients via `RestClient.Builder#defaultStatusHandler` — e.g. GitHub 401 → "Your GitHub connection has expired. Please log out and reconnect your GitHub account," 404 → "The repository or branch was not found on GitHub...", 5xx → "GitHub is currently unavailable." `IndexingService` now only ever stores a message when it's confirmed `UserFacingException`-safe; everything else gets a generic fallback sentence while the real exception is still logged in full server-side.
- The chat retrieval step (`CodeContextRetriever` → embeddings) ran before the SSE emitter existed, so an embeddings failure bypassed `ChatStreamHandler`'s carefully-built graceful-degradation path entirely. Now inherits the same clean `ExternalServiceException` translation automatically, and (traced through carefully) resolves as a normal synchronous JSON error response, not the SSE content-type conflict fixed earlier — this is a genuinely different code path from that bug.
- Per-file GitHub fetch failures during indexing were logged and silently dropped — a repo could finish "READY" while GitHub was actually failing on a large fraction of files, with zero signal to the user. Now tracked and surfaced: `markReady` stores a soft, non-failure notice ("N file(s) could not be fetched... chat answers may be based on an incomplete index") when any files were skipped, and the dashboard shows it in a new amber "Indexed with warnings" variant of the existing error alert (distinct from the red "Indexing failed" one).
- Stale-vector cleanup failure on re-index was silently swallowed, letting a repo re-index on top of un-deleted old vectors (delayed, hard-to-diagnose degraded chat answers). Now a hard failure with a clean message — an honest failure beats silent data corruption.
- 429 rate-limit detection in `IndexingService` was a fragile string-match on the exception message; now also checks the new typed exception's actual upstream status.

**Frontend — the dominant failure mode, per the audit, was raw browser/parser text reaching the user verbatim:**
- `apiFetch` never caught a rejected `fetch()` promise (network down, DNS/CORS failure, offline) — every mutation/query downstream showed the browser's raw `"Failed to fetch"` / `"Load failed"` text. Now wrapped into a clean `ApiError`, plus a 20s timeout (via `AbortSignal.any`) so a hung backend call fails with a message instead of spinning forever.
- `streamChatMessage`'s `fetch()` had the identical gap — fixed the same way. Malformed SSE JSON payloads used to surface the raw `SyntaxError` text (e.g. "Unexpected token h in JSON at position 0"); now a clean fallback message. SSE `error` events used the raw server text as the `Error` message with no parsing; now defensively parsed for a `.message`/`.error` field with a safe, length-capped fallback.
- No global 401 handling existed outside the `auth/me` query — a session expiring mid-use left the user stuck on a broken page with a generic error, not redirected to `/login`. `query-provider.tsx` now has a `QueryCache`/`MutationCache` with a global `onError` that redirects to `/login` on any 401, anywhere in the app.
- Several mutations had no `onError` at all (silent failure); several queries (`useChatSessions`, `useChatMessages`) had no error surfacing and their real callers never checked `isError`, so a failure looked identical to "nothing here yet" (empty sessions list, empty chat history). Added a safety-net toast for mutations lacking their own handler (skipped for ones that already have one, so no double-toasting), and threaded `isError`/retry through `ChatSidebar` and `ChatMessages` (via a new `ChatConversation` prop chain) so both now show a real inline error + retry button.
- No default `retry` policy — even a permanent 401/404 got TanStack Query's default 3 retries with backoff before any error appeared. Added a default `retry` that skips retrying 4xx `ApiError`s.
- `RequireAuth` conflated a real "not logged in" (401) with a transient failure (network blip, backend outage) — both silently bounced to `/login` with a blank-flash and no explanation. Now distinguishes them: a real 401 redirects as before; anything else shows a "couldn't verify your session" message with a retry button, and the spinner stays visible instead of flashing to blank.
- Zero Next.js error boundaries existed anywhere (no `error.tsx`, no `global-error.tsx`) — any uncaught render-time error crashed to Next's raw dev overlay or a blank production page. Added both: `error.tsx` for route-level errors (styled, with retry + back-to-dashboard), `global-error.tsx` as a last-resort fallback for failures in the root layout itself (plain inline styles, since it can't rely on the app's own CSS being available).

### Efficiency & resilience pass — incremental re-indexing, Groq fallback, rate limiting (2026-09-03, fourth pass)

Prompted by a question about Gemini's free-tier rate limits (100 RPM / 30k TPM) turning into three concrete asks: cut wasted embedding spend on re-indexes, add resilience against a single Groq model being unavailable, and stop reacting to 429s after the fact instead of pacing proactively.

- **Incremental re-indexing.** `IndexingService.doIndex()` used to wipe every vector for a repo and re-embed the entire tree on *every* index run, including a no-op re-index of an unchanged repo. Added a new `indexed_files` table (`IndexedFile` entity, `IndexedFileRepository`, migration `V5__create_indexed_files.sql`) tracking each file's last-indexed GitHub blob SHA. A re-index now diffs the current tree against that table: unchanged files (SHA match) are skipped entirely — no fetch, no chunking, no embedding call; changed files have their old vectors cleared (via a `repoId AND filePath IN (...)` filter, not a full-repo wipe) before being re-embedded; removed files have their vectors and tracking row deleted. A file's `IndexedFile` row is only written after its chunks are *successfully* persisted to the vector store, so a failed run never leaves a file wrongly marked "already indexed."
- **Groq model fallback.** `ChatStreamHandler` now tries a configurable ordered list of models (`spring.ai.openai.chat.options.model` + `app.groq.fallback-models`, defaulting to `openai/gpt-oss-20b,qwen/qwen3.8-27b` — chosen from this account's actual Free Plan model list, each with its own separate quota bucket from the primary). Implemented via `Flux.onErrorResume` around each model attempt: it only falls back if the failing attempt produced zero output tokens so far, so a mid-answer failure never gets spliced together with a second model's continuation.
- **Proactive rate limiting.** New `TokenBucketRateLimiter` (continuous-refill, shared per external account) paces outbound Gemini embedding calls and Groq chat calls against configurable RPM/TPM budgets (`app.rate-limit.gemini.*`, `app.rate-limit.groq.*` — Groq defaults taken from this account's real Free Plan numbers) *before* firing each request, instead of only reacting to a 429 after it happens. The existing reactive 429-retry in `saveBatchWithRetry` stays as a backstop. Also added a separate per-user (per-IP if unauthenticated) request throttle (`ApiRateLimitInterceptor`, `app.rate-limit.api.rpm`, default 120/min) on our own `/api/**` endpoints, returning the app's standard JSON error shape on 429 — live-tested via curl (120 requests succeeded, the 121st+ correctly got 429, and an auth-gated endpoint still correctly returned 401 instead of being short-circuited by the new limiter).

Verified: clean backend recompile, boot test against the live dev DB (Flyway applied `V5` cleanly, all new beans — including the two differently-configured `TokenBucketRateLimiter` instances via `@Qualifier` — wired with no conflicts), and a live curl smoke test of the new per-IP rate limit (429 after the configured threshold, correct JSON body, 401 still takes precedence on protected routes). Groq's daily caps (RPD/TPD) aren't tracked, only the per-minute ones — noted as a known gap, not silently claimed as covered.

### Bugs found and fixed live, getting indexing and chat working (2026-09-03, first pass)

- **Embedding dimension mismatch broke all indexing** (`expected 768 dimensions, not 3072`) — `GeminiEmbeddingModel` was hardcoded to `gemini-embedding-001` (3072-dim default) while pgvector was provisioned for 768. Fixed by keeping `gemini-embedding-001` (the only model Gemini's OpenAI-compatible endpoint actually serves) but explicitly requesting `768`-dim output via the `dimensions` request parameter, sourced from `spring.ai.vectorstore.pgvector.dimensions` so the two can't drift apart again.
- **Subdirectory files silently skipped during indexing** (originally issue #5) — `GithubApiClient.getFileContent` passed a multi-segment path as a single URI template variable, so Spring's default encoding turned internal `/` into `%2F`, breaking GitHub's contents API for any nested file. Fixed by encoding each path segment individually and building the URI with literal `/` separators.
- **Groq chat model decommissioned, then not available on this account's plan** (originally issue #30) — `llama-3.1-70b-versatile` was decommissioned; the replacement `llama-3.3-70b-versatile` turned out not to be on this account's Free Plan. Switched to `openai/gpt-oss-120b`, confirmed available.
- **SSE stream errors crashed with a secondary, unrelated-looking exception** — `emitter.completeWithError()` made Spring MVC re-dispatch through the normal `@ExceptionHandler` chain, which then failed trying to write JSON onto a response locked to `text/event-stream`. Fixed by sending a proper SSE `error` event and calling `emitter.complete()` instead.

### Backend reliability pass (2026-09-03, second pass)

- **#3 — OAuth login could 500 on a reclaimed GitHub username.** `UserService.upsertFromGitHub` now proactively frees a stale username off any old local row before saving, instead of letting the DB's unique constraint reject the insert.
- **#4 — Concurrent index requests could race.** `startIndexing` now does a single atomic conditional `UPDATE ... WHERE index_status <> 'INDEXING'` (`RepositoryRepository.tryStartIndexing`) instead of a separate find-then-save, closing the check-then-act window. This also incidentally fixed **#26** (frontend polling missing the `PENDING`→`INDEXING` transition window) — the status flip is now guaranteed to have already happened by the time the `202` response reaches the client.
- **#6 — Chat state bled across sessions/repos.** `ChatView` is now `key`ed by `repoId`; a new `ChatConversation` component (owning `useStreamChat`) is `key`ed by `sessionId`, so switching either forces a fresh instance instead of carrying over stale streaming state.
- **#7 — `requiredById` threw a plain `RuntimeException`** → now throws `NotFoundException`, giving a proper 404 instead of 500.
- **#8 — Catch-all exception handler leaked raw exception messages.** Now logs the full exception server-side and returns a generic message to the client.
- **#9 — GitHub tree truncation was never checked.** Now logged as a warning when GitHub reports `"truncated": true`.
- **#10 — Vector batch write failures were silently swallowed.** 429 detection now checks the actual HTTP status (not just a string match) as well as the message fallback; after retries are exhausted, the batch failure now throws (failing the job honestly) instead of logging and continuing with a `chunkCount` that includes vectors that were never persisted.
- **#11 — Async dispatch failure could leave a repo stuck in `INDEXING` forever.** `RepoController` now catches a synchronous dispatch failure and calls a new `IndexingService.failIndexing()` so the job cleanly fails and can be retried.
- **#12 — Citations always had `null` startLine/endLine.** `CodeChunker` now locates each chunk back in the source file (best-effort, tolerant of the splitter's whitespace trimming) and populates real line ranges.
- **#13 — Partial assistant replies were lost on stream error.** `ChatStreamHandler` now persists whatever was generated so far before sending the error event, instead of discarding it.
- **#14 — No timeout on the Gemini embeddings HTTP call.** 10s connect / 30s read timeout added.
- **#15 — Prompt-injection surface from unescaped retrieved code.** `ChatPromptBuilder` now wraps code context in explicit delimiters with an instruction to treat it as data, not instructions.
- **#16 — Dead `V1_init_schema.sql` migration.** Removed (it was never actually picked up by Flyway due to its filename); replaced with a properly named `V3__create_extensions.sql`.
- **#17 — No foreign-key constraints anywhere.** Added in `V4__add_foreign_keys_and_indexes.sql`, all `ON DELETE CASCADE` (user → repos/sessions, repo → sessions, session → messages).
- **#18 — Missing indexes on `chat_sessions.user_id`/`repository_id` and `chat_messages.session_id`.** Added in the same migration.
- **#19 — `flyway-core` pinned to an old version, overriding the Spring Boot BOM.** Removed the pin — but this actually broke Postgres support entirely (see "found along the way" below); real fix required an additional dependency, not just removing the old one.
- **#21 — Open redirect via the `next` login query param.** `?next=//evil.com` no longer passes the safety check (now rejects protocol-relative paths, not just requiring a single leading `/`).
- **#23 — Streamed answer could get stuck rendering forever.** `useStreamChat`'s `finally` block now unconditionally clears `streamText`/`streaming` regardless of how the stream ended.
- **#24 — Silent GitHub resync on every tab focus.** Disabled React Query's default `refetchOnWindowFocus` globally (the empty-list-triggers-one-resync behavior on initial load was left as-is — that part looks intentional).
- **#28 — No HTTP timeouts on GitHub API calls.** 10s connect / 30s read timeout added.
- **#29 — `listUserRepos` silently capped at 1000.** Now logs a warning when the cap is hit.
- **#31 — Unchecked casts / no null check on the Gemini embeddings response.** Now throws a clear `IllegalStateException` on a malformed response instead of an opaque NPE.
- **#32 — Chat messages had no length cap.** Added `@Size(max = 8000)` — see also the validation-provider fix below, without which this annotation would have been silently inert.
- **#34 — Cookie-name constant duplicated in two files.** Extracted to `client/lib/auth-cookie.ts`, imported by both `proxy.ts` (edge middleware) and `hooks/use-auth.ts` (client component) rather than each declaring its own copy.
- **#35 — `RequireAuth`'s redirect dropped the return destination.** Now includes `?next=<path>`, matching the middleware's redirect.
- **#37 — `stream-chat.ts` double-invoked `onDone` and silently dropped unrecognized SSE events.** Now fires `onDone` exactly once (after the read loop), and `error` events are now parsed and surfaced via a new `onError` handler wired up in `useStreamChat`.
- **#38 — Enter-to-send didn't check IME composition state.** Added `!e.nativeEvent.isComposing`.
- **#40 — `FAILED` repos with no `errorMessage` showed nothing.** Now falls back to a generic explanation instead of silently showing just the badge.

### Found (and fixed) along the way — not in the original report

- **Bean Validation had no provider on the classpath.** `jakarta.validation-api` alone is just annotations; without `spring-boot-starter-validation` (which pulls in Hibernate Validator), `@NotBlank`/`@Size`/`@Valid` were silently no-ops everywhere in the app, including the pre-existing `@NotBlank` on chat/session request DTOs. This was caught by boot-testing after the #19 Flyway fix surfaced a warning in the startup log. Added the starter.
- **Removing the Flyway version pin (#19) broke Postgres support entirely** — confirmed by boot-testing: `Unsupported Database: PostgreSQL 16.15`. Flyway 10+ split per-database support out of `flyway-core` into separate artifacts; the old pin to 9.22.0 was likely someone's workaround for this exact issue rather than an oversight. Real fix: keep the pin removed (so Flyway tracks the Spring Boot BOM properly) and add the missing `flyway-database-postgresql` dependency.
- **`GithubApiClient`'s shared `RestClient.Builder` bean was being mutated per-request.** It's a singleton, and `.baseUrl()`/`.defaultHeader()` mutate a builder in place — concurrent indexing threads calling it simultaneously could stomp on each other's base URL/auth header. Found while adding HTTP timeouts to this same bean. Fixed with `.clone()` before mutating.

---

## Remaining — requires a manual action or a separate effort

### 🔴 1 & 2. Real secrets hardcoded as fallback defaults; weak token-encryptor key material
- **File:** `backend/src/main/resources/application.properties`
- Every secret-shaped property (`GROQ_API_KEY`, `GEMINI_API_KEY`, GitHub OAuth `client-id`/`client-secret`, `app.token-encryptor-password`/`salt`) ships with a real-looking literal default. This can't be fixed by editing code alone — it needs the project owner to rotate each credential in its respective console (Groq, Google, GitHub OAuth App settings) and supply the new values only via environment variables, with no committed fallback. Happy to make the code change (strip the defaults so the app fails fast instead of silently running insecurely) once you've got replacement values ready — doing it now would just break the app you're actively testing.

### ⚪ 20. Zero real test coverage
- The only test is a `@SpringBootTest contextLoads()` requiring a live Postgres. Writing meaningful coverage (repositories, controllers, the OAuth2 flow, the indexing pipeline) is a substantial separate effort, not a quick fix — worth its own pass if you want it.

### 🟡 22. No CORS diagnostics
- `client/next.config.ts` has no rewrite/proxy; every API call is cross-origin, resting on the backend's CORS + cookie config being correct. Not really a code fix — this is a "verify your deployment's CORS/cookie settings" item, most relevant once this moves beyond `localhost`.

### ⚪ 27. Persisted token scope is a hardcoded fallback
- `GithubOAuth2UserService.java:24` — data-accuracy issue only (nothing currently gates access on this field). Low value to fix in isolation; left as-is.

### ⚪ 33. `proxy.ts` route gate relies on a plain JS-writable cookie
- Confirmed **no change needed** — `RequireAuth` independently re-verifies against the real backend session, so this isn't an actual security hole today. Noted as a trap for any *future* route that skips `RequireAuth`.

### ⚪ 39. "Sync" button appears disabled/spinning during unrelated background polls
- `repo-dashboard.tsx` ties `isSyncing` to `reposQuery.isFetching`, which also flips on the ~2s indexing-driven background poll. Minor UX nit, deferred.

---

## What to check next

Indexing and chat are both confirmed working end-to-end as of this session. The highest-value remaining item is the credential rotation (#1/#2) — say the word when you're ready and I'll strip the insecure defaults right after. Everything else remaining is either intentionally deferred (needs its own design/effort) or genuinely low-priority.
