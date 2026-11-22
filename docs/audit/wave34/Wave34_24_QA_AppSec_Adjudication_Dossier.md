# Wave 34.24 — QA/AppSec Independent Review Dossier

**Prepared for independent review, NOT an independently signed review. November 22, 2026. PRODUCTION NO-GO.**

## Issue #51: settlement approval UUID collision during concurrent replay

Original Java11 PG16 failed [Actions #42709465166](https://github.com/danwiz/Project0-Cardealership-Maven/actions/runs/42709465166) at `b78543deb199b2a5d6f0a3a0d58965a42ede0ca7` with SQLSTATE 23505 on unique `w3416_batch_reviews_maker_approval_id_key`. The *synthetic* original insert targeted `ON CONFLICT (batch_id) DO NOTHING` but maker/checker approval UUIDs were also unique. The later fixture (read from PR #57's exact head) uses untargeted `ON CONFLICT DO NOTHING`, then exact batch-ID readback of review digest, maker/checker UUIDs, gross/fee/net. A different batch reusing an approval must be rejected, not treated as exact replay.

Synthetic remediation evidence: Wave34.21-B [CI #42729851373](https://github.com/danwiz/Project0-Cardealership-Maven/actions/runs/42729851373) had 2,000 repeated four-worker invocations for #51 (1,000 per JDK); Wave34.22 [#42736600354](https://github.com/danwiz/Project0-Cardealership-Maven/actions/runs/42736600354) forced unique-index waiting and cross-batch identity rejection. No real bank, GL provider or certified maker-checker workflow was verified.

**State: REMEDIATION EVIDENCE PRESENT; INDEPENDENT REVIEW NOT DONE; ISSUE OPEN.**

## Issue #53: GL provider-event identity collision

Original Java11 PG16 [Actions #42714152238](https://github.com/danwiz/Project0-Cardealership-Maven/actions/runs/42714152238) returned SQLSTATE 23505 on `w3415_gl_events_provider_event_id_key` during concurrent exact replay. The earlier *synthetic* insert had `ON CONFLICT (event_id) DO NOTHING`, though provider-event, sequence and approval IDs were unique. The corrected inherited fixture uses `ON CONFLICT DO NOTHING` plus exact event-ID readback checking sequence, event kind, minor amount, currency, digest, and approval UUID; deferred two-line balance gate retained.

Synthetic remediation evidence: Wave34.21-B [CI #42729851373](https://github.com/danwiz/Project0-Cardealership-Maven/actions/runs/42729851373) 2,000 four-worker invocations for #53; Wave34.22 [#42736600354](https://github.com/danwiz/Project0-Cardealership-Maven/actions/runs/42736600354) forced unique waits, SQLSTATE 23514 deferred-balance rollback and 40P01 deadlock recovery. No live accounting, provider authenticity or finance authorization is proven.

**State: REMEDIATION EVIDENCE PRESENT; INDEPENDENT REVIEW NOT DONE; ISSUE OPEN.**

## Reviewer handoff and acceptance criteria

For each issue: independent QA and AppSec reviewers (not the implementer) examine the original failing run, repaired exact SHA, all relevant UNIQUE indexes, idempotent identity oracle, negative collision case, transaction rollback, durable row counts, and independently authored adversarial test. Record reviewer GitHub identity, evidence SHA, scope limits, signed review, and acceptance or changes requested. A green CI run or this assistant-created dossier **cannot** constitute independent signoff. Do not close either issue or merge/approve the PR chain without distinct owner authorization.

| Decision item | Issue #51 | Issue #53 |
| --- | --- | --- |
| Original CI failure | LINK VERIFIED | LINK VERIFIED |
| Repaired synthetic test source | OBSERVED | OBSERVED |
| Independent QA reviewer | UNASSIGNED | UNASSIGNED |
| Independent AppSec reviewer | UNASSIGNED | UNASSIGNED |
| Reviewer-controlled negative reproduction | NOT RUN | NOT RUN |
| Signed review | NOT RECEIVED | NOT RECEIVED |
| Closure authorization | NOT GRANTED | NOT GRANTED |
| Production release | NO-GO | NO-GO |

Even a future synthetic-test approval does not clear W3422-09, W3422-10, the 12 Wave34.19 gates, Stage27/Phase16, provider PVA-01..16, or financial Controller approval.
