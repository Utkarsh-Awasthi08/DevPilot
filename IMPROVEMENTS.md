# DevPilot — Improvement Roadmap

Compiled 2026-09-03 from a three-angle research pass: a fresh audit of the actual codebase (beyond what `ISSUES.md` already covers), research on what actually moves the needle with recruiters/hiring managers for a project like this, and research on what RAG techniques add genuine technical depth beyond the basic chunk-embed-retrieve pipeline already built. Ordered by leverage, not by category — do the top of this file before the bottom.

This is a suggestions/roadmap doc, distinct from `ISSUES.md` (which tracks bugs found and fixed). Nothing here has been implemented yet.

---

## The headline finding: deployment is the bottleneck, not features

Recruiters rarely clone and run a project locally — a live demo link is the single biggest credibility jump available. Right now DevPilot has no path to running anywhere but a developer laptop: `docker-compose.yml` only containerizes Postgres for local dev, there's no `Dockerfile` for the backend or frontend, and no deployment manifest of any kind.

Deploying also makes the secrets-rotation item in `ISSUES.md` (#1/#2) non-optional — the app currently ships real-looking API keys as hardcoded fallback defaults in `application.properties`, which can't go anywhere public as-is.

**Suggested free/near-free stack:**
- **Frontend:** Vercel — frictionless for Next.js, effectively the default choice.
- **Backend:** Render's free tier supports web services + Postgres with no credit card and no Dockerfile requirement, making it the easiest zero-cost option. Railway is faster to deploy but its free credit only covers a few hours/month. Fly.io no longer has a free tier for new users.
- **Postgres + pgvector:** Both Neon and Supabase support pgvector free. **Neon is the better fit** — it scales to zero after ~5 min idle and resumes in ~1 second. Supabase free projects now pause entirely after 7 days of inactivity and need a manual dashboard unpause — a real risk if a recruiter opens your demo link a week after you last touched it.

---

## Tier 1 — README + story (small effort, largest signal-per-hour)

There is currently no root `README.md`. `client/README.md` is unmodified `create-next-app` boilerplate — it documents nothing about DevPilot itself, no required env vars, no setup steps. `ISSUES.md` is an audit log, not onboarding material.

A README that reads as senior rather than tutorial-follow-along should contain, in order:
1. A screenshot/GIF of the actual chat-with-your-codebase UX in the first screenful.
2. A short "why I built this" framing tied to a real problem — not "learning project."
3. **Two** architecture diagrams (Mermaid renders natively in GitHub markdown): the ingestion pipeline (GitHub OAuth → repo pull → chunking → embeddings → pgvector) separately from the query pipeline (query → retrieval → LLM → streamed response). The split itself signals you understand RAG isn't one box.
4. A "Technical Decisions / Tradeoffs" section — why Spring Boot, why Postgres/pgvector over a dedicated vector DB, the chunking strategy and why, what you'd change at 10x scale.
5. A "known limitations" section — admitting gaps reads as senior, not weak.

**Bigger lever than it sounds:** a written **case study** (1000–1500 words — "how I chunk code for retrieval," "my first RAG approach gave bad answers, here's what I changed," "what it costs per query and how I cut it") does more for interview conversations than another feature, and is the best lever specifically for AI-adjacent roles since it shows eval thinking in prose.

**Low leverage, don't over-invest:** coverage/CI badges (a stale or fake one is worse than none) and a public roadmap/issues board (matters for attracting open-source contributors, not for a solo portfolio piece recruiters screen).

---

## Tier 2 — the one RAG feature that's an actual differentiator right now: evals

"Wrap an LLM API" is a cliché in 2026 — interviewers actively discount it unless there's evidence of deeper engineering. Almost no portfolio RAG projects run evals, which makes this the single highest-leverage technical addition available.

- Hand-curate 50–150 real question/answer pairs from repos you've indexed (a golden set), store as JSON.
- Score with **RAGAS** (open-source): faithfulness (claims supported by retrieved context), answer relevancy, context precision/recall. Cited 2026 production targets: faithfulness ≥0.75–0.9, relevancy ≥0.8–0.85.
- Use a *different, stronger* judge model than the one that generated the answer — same-model grading has known self-bias.
- Publish the scores in the README.
- Pair with basic cost/latency tracking ($/query, p50/p95 retrieval and end-to-end latency) — showing you know where your latency budget goes (retrieval vs. LLM call) reads as production thinking, not toy thinking. Cheap to add given the rate limiter already tracks most of the needed data.

---

## Tier 3 — RAG technique upgrades worth their effort

Suggested order (best quality-per-effort first):

1. **Hybrid search (BM25 + vector, fused via Reciprocal Rank Fusion).** Matters specifically for code: exact identifiers, error strings, and config keys are exactly what dense embeddings retrieve worst, and lexical search nails them. Postgres `tsvector`/`tsquery` gets this with zero new infra (or the ParadeDB `pg_search` extension for real BM25 scoring). Spring AI's `VectorStore` is dense-only, so this means one hand-written query unioning vector-topK and BM25-topK, fused in Java. *Effort: Medium.*
2. **Query rewriting/expansion (HyDE or multi-query).** Have the LLM generate a hypothetical answer to embed instead of the raw question, or expand into paraphrases searched in parallel — bridges natural-language questions ("why does login fail") to code vocabulary (`AuthenticationException`). One extra Groq call. *Effort: Small.*
3. **One agentic tool** (e.g. `open_file(path, lineRange)` or a ripgrep/`ast-grep`-backed search tool) via Groq's tool-calling API. Genuinely differentiated for code specifically — code has explicit structure (imports, call graphs) that flat embeddings discard, so a tool that can just go look beats semantic guessing on structural questions. Standard agent loop layered on the existing SSE stream. *Effort: Medium.*
4. **AST-aware chunking** (tree-sitter, chunk at function/class boundaries instead of token windows) — meaningfully better than naive chunking for code specifically, since naive chunking regularly splits a function mid-body. *Effort: Medium–Large — requires a new chunking pipeline per language and re-embedding everything.* Treat as a stretch goal.

**Skip for now:** GraphRAG / code-dependency-graph retrieval and full multi-hop cross-repo agentic reasoning — the largest lifts on the list, not worth it unless going deep specifically for AI-engineering roles. DevPilot's existing shared-table-plus-`repo_id`-filter design already matches published prior art (Multi-Meta-RAG) and doesn't need to change for single-repo use.

---

## Tier 4 — maturity signals that are cheap and double as case-study material

Small/trivial effort, and each one reads as "found and fixed a real issue" in an interview conversation:

- **Basic CI** — no `.github/workflows/` exists; `mvn test`, lint, and `tsc --noEmit` are only ever run by hand today.
- **Spring Boot Actuator** — no `spring-boot-starter-actuator` dependency; nothing exposes `/actuator/health` or metrics, so nothing can tell if the app is actually up.
- **CSRF is fully disabled** (`SecurityConfig.securityFilterChain()`) with no compensating Origin-header check — relies entirely on `SameSite=Lax`.
- **Token-at-rest encryption uses non-authenticated AES/CBC** (`CryptoConfig.tokenEncryptor()` via `Encryptors.text`) — `Encryptors.delux` (AES/GCM) is a drop-in upgrade with integrity checking.
- **`CreateChatSessionRequest.title` has no `@Size` cap**, but the column is `VARCHAR(200)` — an overlong title hits a raw Postgres error instead of a clean 400.
- **`ApiRateLimitInterceptor` keys anonymous requests by `request.getRemoteAddr()`** — behind any reverse proxy (i.e., the moment you deploy per Tier 0) this collapses to one shared bucket for all anonymous callers. Needs `server.forward-headers-strategy` configured and to honor `X-Forwarded-For` from a trusted hop only.
- **No dependency vulnerability scanning** — no Dependabot config, no `npm audit`/OWASP Dependency-Check step. Worth a `.github/dependabot.yml` at minimum given how bleeding-edge the stack is (Spring Boot 4.1.1, Spring AI 2.0.1, Next.js 16).

---

## Tier 5 — product completeness (the "more useful" half, not just portfolio-facing)

- **No way to delete/disconnect a repository.** `RepoController` has no `DELETE` mapping; vectors and `indexed_files` rows accumulate forever with no way to reclaim storage or stop chatting against an unwanted repo.
- **No chat session management** — can create sessions but never rename or delete one; old sessions accumulate indefinitely.
- **No pagination anywhere in chat history** — `listSessions()`/`getMessages()` fetch everything unbounded, every time.
- **The "Manage on GitHub" account button is permanently disabled** (`settings-dashboard.tsx:129`), and there's no real "disconnect & delete my data" action — "Log out" only clears the session cookie, leaving the encrypted GitHub token and all indexed data intact server-side.

---

## Suggested starting order

1. Deploy it (Vercel + Render + Neon), rotating secrets to real env vars in the process.
2. Write a real README with the two architecture diagrams and a Technical Decisions section.
3. Build the RAGAS eval harness and publish the numbers.
4. Add basic CI + Actuator.
5. Knock out the Tier 4 security quick-fixes.
6. Add hybrid search.

That sequence produces a live, credible, technically-differentiated demo before touching the harder RAG features (agentic tools, AST chunking, GraphRAG) — which are worth doing, but only after the above is solid.
