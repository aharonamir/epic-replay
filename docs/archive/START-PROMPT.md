# Starter prompt for Claude Code

Put this folder's contents at the root of an empty repository, start Claude Code there, fill in the bracketed line, and paste.

```
You are building Epic Replay, a live-demo MVP, with me. I am one person and
have ten working days. The agents are ReAct agents built with openJiuwen
agent-core (pip package openjiuwen) on DeepSeek's deepseek-flash; the flow
between stages is a plain Python runner. The full design is in
specs/001-epic-replay/:
spec.md (what and why; section 10 says what this build defers), plan.md
(stages, narration styles, schedule, gates; section 4.15 says what happens
in place of each deferred part), tasks.md (34 tasks in day order, six
deferred), research.md (providers), data-model.md and contracts/ (schemas,
the three example style packs, stage contracts, provider-adapters.md for
agents and adapters, HTTP API, ui.md for the one page and design.md for how
it looks, with the approved canvas files in design-canvas/). The principles are in
.specify/memory/constitution.md. Read all of them completely before doing
anything else.

Presentation date: [date]

Rules for the whole project:
1. The spec files are the source of truth. If something in them is impossible,
   contradictory or clearly worse than an alternative, stop and tell me. Do not
   quietly change the design. When we agree on a change, update the spec files.
2. Follow tasks.md in the order listed. Tick a task off when its "Done when"
   line is true, and commit after each task with the task id in the message.
   Build the smaller version the task describes, not the full design. Do not
   start a deferred task unless I ask.
3. Do not guess agent-core or provider APIs. The spec describes agent-core
   from its README and wiki, with no code samples found. Install openjiuwen,
   read its source (start at core/single_agent/agents/react_agent.py), and
   use only what you have run. Record findings in
   docs/api-notes.md and docs/provider-notes.md. Model names and prices in
   research.md were checked on 2026-10-11; re-check any you depend on.
4. No paid call until the guards of T008 exist. Before any call that costs
   money, tell me the expected cost and wait for my yes. Never send footage
   through a free-tier key.
5. Children are likely to be in the footage. Only still frames may leave the
   machine, and only to the vision endpoint in .env, inside the request.
   Images never pass through agent-core: tools look at frames and return
   text. No prompt, descriptor or line may state or guess anyone's age, or
   remark on a body. Do not send any clip with a child in it until I tell
   you which provider may see it (spec.md section 8).
6. API keys live in .env, which is never committed. Tell me which keys you
   need and I will add them.
7. Everything code can check or produce is done by code: timing, word budgets,
   reference checks, mixing, assembly. The agents choose what to look at and
   what to write. Their tools are plain Python functions that hold the logic;
   limits and validation live in the tools, not in prompts. Each agent has a
   coded fallback that calls the same tools in a fixed order.
8. Write tests for the deterministic code. Tests run with providers in replay
   mode and no network. No features beyond the task in hand.
9. A narration style is a data file. Never branch on a style id in code. If a
   style needs something the pack cannot express, stop and tell me.
10. The clip can be any moment, not only sport. Prompts must not assume a
    ball, a score or an opponent.
11. There are 2 hours of margin in 80. If a task is running over its hours by
    half again, stop and tell me, and we decide what to cut from the list in
    tasks.md.

First session (day 1):
1. Summarise the pipeline back to me in 12 lines, and list anything in the
   files that looks inconsistent or unclear. Wait for my reply.
2. Write CLAUDE.md with the rules above, the repo layout from plan.md
   section 5 and the commands to run and test the project.
3. Do T001, T007, T009 and T008. I will do T006 (collect the starter clips).
4. Prepare day 2: the T002 throwaway agent (one tool and a submit tool on
   deepseek-flash), the T003 script that turns each starter clip into
   numbered frames and asks the vision model at the settings in the task,
   and the T004 script that renders the test lines in the candidate voices. Tell me the expected cost of each
   and wait before running them.
5. Stop and report: what works, what does not, what you decided, and what you
   need from me for Gate 0.
```

For later sessions:

```
Read CLAUDE.md and specs/001-epic-replay/tasks.md. Continue with the next
unchecked task that is not deferred. Stop at the next gate and report against
its exit criteria in plan.md section 6.
```
