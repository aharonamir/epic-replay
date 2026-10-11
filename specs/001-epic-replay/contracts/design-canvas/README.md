# Canvas files

A copy of the design canvas "Epic Replay page" as approved on 2026-10-11. The canvas itself is at https://claude.ai/artifact/W4itfPg6U9SxjM5xM2UVRW and is private to the owner.

| File | Shows | Size on the canvas |
|------|-------|--------------------|
| `Main.dc.html` | 1 Start | 1280 px wide |
| `Progress.dc.html` | 2 Progress | 1280 px wide |
| `Confirm.dc.html` | 3 Confirm | 1280 px wide |
| `Result.dc.html` | 4 Result | 1280 px wide |
| `StartPhone.dc.html`, `ProgressPhone.dc.html`, `ConfirmPhone.dc.html`, `ResultPhone.dc.html` | The same four files mounted at 390 px wide | 390 px wide |
| `canvas.json` | Where each artboard sits on the canvas | |

How to use them:

- They are reference, not product code. Each is an HTML file for the canvas editor: styles are inline, `{{name}}` marks a value its small script supplies, and `<sc-if>` shows or hides a part. They do not run outside the canvas, because the editor supplies `support.js`.
- Read them for exact values: sizes, spacing, colours, the flex settings that make each area wrap on a phone. [../design.md](../design.md) summarises them and is the contract.
- The phone files contain no layout of their own. The phone layout is what the four main files do at 390 px.
- Pictures are striped placeholders, the vision provider is written as `[VISION SERVICE] ([COUNTRY])`, and the time-and-cost panel is a placeholder. The play card and script are the free-kick fixture.
- If the canvas changes, copy its files here again and update design.md in the same commit.
