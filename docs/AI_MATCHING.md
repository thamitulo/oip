# AI and matching

Two separate mechanisms:

1. **Rule-based matching** (always on, no AI): an explainable 0–100 score for every active opportunity against
   each tenant's company profile.
2. **AI features** (optional, configurable): the in-app assistant, an AI fit assessment of a match, and AI
   classification of opportunities. They use Claude through the official Anthropic Java SDK and are off unless
   `ANTHROPIC_API_KEY` is set on the server.

AI is never the source of truth. Everything it produces is stored separately, labelled `AI`, and recorded with
model, prompt version, tokens and time.

## Rule-based matching (`matching/MatchingEngine`)

| Dimension | Default weight | How it is scored |
|---|---|---|
| Services/products | 30 | Best phrase/word match of a profile service or product in title + description + category |
| Industry | 20 | Profile industries/commodities vs opportunity text; mining profile vs mining-related opportunity |
| Capabilities | 20 | Capability names, then shared terms with capability descriptions |
| Geography | 10 | Operating regions vs province. Unknown province = neutral (0.5) and reported |
| Registrations/certifications | 10 | Against identified requirements: CIDB grade *and* class, CSD, tax, professional bodies, ISO. None identified = neutral |
| Other eligibility | 10 | B-BBEE level vs requirement, compulsory briefing (action), very short time to close |

- Weights and thresholds are per tenant (`/companies/match-settings`, must total 100). Defaults: strong ≥ 70, possible ≥ 45.
- Unknowns are never treated as satisfied. Without any topical alignment the score is capped below "possible".
- **Missing mandatory requirements are disqualifiers**, listed separately, and force the label
  `REQUIRES_MANUAL_REVIEW` regardless of score.
- Matches are cached per tenant (`opportunity_matches`) and recomputed when the profile, weights or the
  opportunity change (profile fingerprint + opportunity content hash). A background job refreshes every 10 minutes;
  a profile save triggers a refresh.
- API: `GET /opportunities/{id}/match`, `GET /opportunities/recommended?minScore=` (also `/opportunities/matches`).

## AI configuration

| Where | What |
|---|---|
| Environment | `ANTHROPIC_API_KEY` (required to enable AI), optional `OIP_AI_BASE_URL` |
| Admin UI → AI settings (`/admin/ai/settings`) | Master switch, model (default `claude-opus-5-5`), effort (default `medium`), per-feature switches, daily limits (assistant per tenant, classification per platform), refusal fallback |
| Status (`/ai/status`) | What each user can use right now |

Request defaults: adaptive thinking (the model default), effort from settings, and the provider's server-side
refusal fallback (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`) on models that support it
(switchable). Refusals are handled explicitly.

## The assistant (`ai/AssistantService`, `ai/AssistantTools`)

A Claude tool-use loop (max 8 rounds) over platform tools that **run as the signed-in user, scoped to their tenant**:

| Tool | Purpose |
|---|---|
| `search_opportunities` | Search active opportunities (keywords, sector, type, province, submission method, closing window) |
| `get_opportunity` | Details incl. submission, briefing, documents, requirements with provenance |
| `get_document_text` | Extracted document text, to answer questions about scope/criteria |
| `get_company_profile` | The tenant's profile (self-declared claims) |
| `get_match` | The rule-based match explanation |
| `list_recommended` | Best matches |
| `get_pipeline` | The tenant's bid pipeline |
| `shortlist_opportunity` | The only write action; used only when the user asks; requires `PIPELINE_EDIT` |

The system prompt requires answers grounded in tool results, provenance to be stated, SAST times, links to
opportunities, and forbids claiming eligibility. The system prompt and tool list are fixed and prompt-cached;
the date and user context go in the user turn. Conversations are stored per user (`assistant_conversations`,
`assistant_messages`, with a tool trace for audit); earlier turns are replayed as text.

## AI fit assessment and classification (`ai/AiAnalysisService`)

Single calls with structured output (schema-validated records):

- **Assess fit** (`POST /opportunities/{id}/match/explain`): summary, recommendation (`PURSUE`, `CONSIDER`,
  `UNLIKELY_FIT`, `INSUFFICIENT_INFORMATION`), strengths, gaps, questions — given the opportunity, document
  excerpts, profile and the rule-based match. Stored on the tenant's match; cleared when the profile changes.
- **Classify** (`POST /opportunities/{id}/classify`): industries, services, products, commodities, requirements with
  quoted evidence, summary, confidence → `ai_classification_results`, plus `opportunity_categories` and
  `opportunity_requirements` with provenance `AI` (previous AI rows replaced; source/document rows untouched).

## Audit and cost control

`ai_processing_audit` records tenant, user, feature, provider, model, prompt version, status
(`SUCCEEDED/FAILED/REFUSED/LIMITED`), tokens, tool calls and latency. Daily limits are enforced from it; the admin
AI page shows 30-day usage.
