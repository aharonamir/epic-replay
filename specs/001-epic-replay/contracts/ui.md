# The page

Revision 5 (2026-10-11): layout and wording brought in line with the design the owner approved on the design canvas. What the page does is unchanged from revision 3. Colours, type and measurements are in [design.md](design.md).

The MVP is one web page. It never navigates away: the same page moves through four states. It must work in a phone browser as well as on a laptop, because people will upload straight from the phone they filmed on. The wireframes below show the laptop layout; on a phone every two-column area becomes one column, in the order given under each wireframe.

Address: `/` for a new run, `/r/<run_id>` for an existing one. Reloading `/r/<run_id>` restores the state from `GET /runs/{id}` and re-subscribes to progress.

## States

| State | Shown when | Main content |
|-------|-----------|--------------|
| 1. Start | no run yet | Upload, narration style, optional details, consent, Go |
| 2. Progress | run is `running` | Steps with live status, elapsed time |
| 3. Confirm | run is `waiting_play_card` | The play card, inside the progress list |
| 4. Result | run is `done` | Video, download, try another style, details |
| Stopped | run is `stopped` or `failed` | What happened in plain words, and what to do |

Every state has the same header: the wordmark "Epic Replay" with a rewind mark, in the colour of the chosen narrator.

## 1. Start

```
+------------------------------------------------------------------------------+
|  << EPIC REPLAY                                                              |
+------------------------------------------------------------------------------+
|  FILM IT.                             WHO'S NARRATING?                       |
|  WE'LL CALL IT.                       +----------------------------------+   |
|                                       | (o) Football commentator         |   |
|  +-                              -+   |     Excited play-by-play from    |   |
|  |                                |   |     the stadium booth.  [> Listen]|  |
|  |   Drop your clip here, or      |   |     [OFF THE POST AND IN! ...]   |   |
|  |   choose a file.               |   +----------------------------------+   |
|  |   Any great moment.            |   | ( ) News reporter                |   |
|  |                                |   |     Your moment as tonight's     |   |
|  |       [ Choose a file ]        |   |     top story.          [> Listen]|  |
|  |                                |   |     [TOP STORY | The ball ...]   |   |
|  |   3 to 30 seconds · MP4, MOV   |   +----------------------------------+   |
|  |   or WebM · up to 200 MB       |   | ( ) Nature documentary           |   |
|  +-                              -+   |     A hushed narrator observes   |   |
|  Tip: film in slo-mo mode for a       |     the scene.          [> Listen]|  |
|  sharper replay.                      |     And there... off the post... |   |
|                                       +----------------------------------+   |
|                                       OPTIONAL                               |
|                                       Who's the star? [______________]       |
|                                       [ ] Remove the clip's own sound        |
|  --------------------------------------------------------------------------  |
|  [x] I filmed this or have permission to use it. Everyone                    |
|      in it is an adult man who is happy to be filmed.           +---------+  |
|      I have read the note below.                                | MAKE MY |  |
|      Still frames from the clip are sent to <service>           | REPLAY  |  |
|      (<country>) to be looked at. Nothing else leaves           +---------+  |
|      this machine.                                                           |
+------------------------------------------------------------------------------+
```

Order on a phone: headline, drop area, tip, narrators, optional fields, consent, button.

- The headline is the tagline, "Film it. We'll call it.", with its second line in the narrator's colour.
- After a file is chosen the drop area becomes a preview: the clip's first frame, the file name and length, and a "Change" button. Length and type are checked in the browser first, then again by the server (FR-001).
- Style cards come from `GET /styles`, in the packs' `order`. The default pack is preselected. Each card shows the pack's name, its description and its sample line set as that narrator's own caption. "Listen" plays the pack's sample; only one sample plays at a time.
- Choosing a narrator recolours the page in that pack's accent.
- "Make my replay" is disabled until there is a valid file and the consent box is ticked.
- The line under the consent box names the vision provider and its country from configuration, so it stays true if the provider changes. It makes no promise about deletion unless the configured provider's terms give one, and says plainly when those terms allow training on what is sent.
- Score and team fields are in the API but not on the page in the one-person MVP, because the score bug is deferred.
- On a phone the file control opens the camera roll, and "Listen" moves under the card's text.

## 2. Progress

```
+------------------------------------------------------------------------------+
|  << EPIC REPLAY                               o Football commentator  0:42   |
+------------------------------------------------------------------------------+
|  MAKING YOUR REPLAY                                              Step 5 of 8 |
|  [#######][#######][#######][#######][       ][       ][.......][.......]    |
|                                                                              |
|  +-                -+      v  Checking your clip                       2 s   |
|  |                  |      v  Watching what happens                   21 s   |
|  |    your clip     |      v  Finding the key moment                   8 s   |
|  |                  |      v  You confirmed what happened                    |
|  +-                -+      @  Writing the call                      WORKING  |
|                            @  Building the slow-motion replay       WORKING  |
|  ABOUT A MINUTE            o  Recording the voice                            |
|  TO GO.                    o  Mixing and finishing                           |
|                                                                              |
|                                                         [ Show details ]     |
+------------------------------------------------------------------------------+
```

Order on a phone: bar, clip, time left, steps, "Show details".

Steps the user sees, and the stages behind each:

| # | Step | Stages |
|---|------|--------|
| 1 | Checking your clip | S0 (and S1 when it is built) |
| 2 | Watching what happens | S2 |
| 3 | Finding the key moment | S3 |
| 4 | Is this what happened? | Checkpoint A |
| 5 | Writing the call | S4, S5, S6 |
| 6 | Building the slow-motion replay | S9 |
| 7 | Recording the voice | S7 |
| 8 | Mixing and finishing | S8, S10 |

- The header shows the narrator and the time since the run started.
- A step is waiting (`o`), running (`@`, with the word "Working"), done (`v`, with its duration), or done with a note when a fallback was used; the note is one plain sentence under the step name, for example "Used a simpler slow motion."
- Once answered, step 4 reads "You confirmed what happened".
- Steps 5 and 6 run at the same time and both show as running.
- The bar has one segment per step: filled when the step is finished, outlined while it runs, empty before. It advances when a step finishes and does not animate on a timer. "Step N of 8" names the lowest-numbered step that is not finished.
- The time left comes from the measured median duration of the remaining steps, in three wordings: "A few seconds to go.", "About half a minute to go.", "About a minute to go."
- "Show details" adds a line under each step with what FR-036 asks for: stage id, the agent or tool that ran, duration, cost, and a one-line summary of what it produced. For the two agents it lists each tool call in order ("look 0 to 15 s at 4 fps", "zoom frames 283 to 343", "check_lines: 1 line over budget", "submit_script"). This is the view for a demo audience. It is closed by default, and the button then reads "Hide details".
- Updates arrive by server-sent events. If the connection drops, the page polls `GET /runs/{id}` every two seconds until it reconnects.

## 3. Confirm

Step 4 opens in place. The bar shows three segments filled and the fourth outlined, the line beside the clip reads "Your turn.", and nothing else on the page changes.

```
|   @  Is this what happened?                                                  |
|      +--------------------------------------------------------------------+  |
|      |  +------------------+    FOOTBALL FREE KICK                        |  |
|      |  |  frame of the    |    1  The player in red places the ball      |  |
|      |  |  key moment      |       for a free kick.                       |  |
|      |  +------------------+    2  Four players in blue line up as a wall.|  |
|      |  Frame 313 · 10.43 s     3  The player in red strikes the ball.    |  |
|      |  [<<][< Earlier]         4  The ball goes into the top-left        |  |
|      |  [Later >][>>]              corner.  [GOAL]                        |  |
|      |  [This one]                                                        |  |
|      |  Other moments:                                                    |  |
|      |  [7.9 s] [12.2 s] [15.0 s]                                         |  |
|      |                          + - - - - - - - - - - - - - - - - - - -+  |  |
|      |                          | 5  The goalkeeper does not dive.      |  |  |
|      |                          |    CHECK THIS    [ Keep ] [ Remove ]  |  |  |
|      |                          + - - - - - - - - - - - - - - - - - - -+  |  |
|      |  ----------------------------------------------------------------  |  |
|      |  [ YES, THAT'S IT ]     [ Fix something ]     [ Not at all ]       |  |
|      |  The rest will cost about $0.04.                                   |  |
|      +--------------------------------------------------------------------+  |
```

Order on a phone: frame, frame number, nudge buttons, other moments, activity, events, the three buttons one under another, cost.

- Items the system is unsure about are outlined, carry the words "Check this", and have "Keep" and "Remove". They must be answered before "Yes, that's it" is enabled (FR-010).
- "Fix something" makes the activity and the outcome sentence editable in place. Editing each event is in the full design; in the one-person MVP events can be kept or removed, not rewritten.
- "Not at all" offers two choices: type one line about what happened and try again, or continue with a narration that makes no specific claims (FR-012).
- "Earlier" and "Later" move the key moment one frame at a time, and the frame number and time under the picture follow; holding a button moves five frames a second. "10 back" and "10 on" (the `<<` and `>>` buttons) move it ten frames at a time, by the same mechanism.
- The key moment is always an item to check (revision 8): it carries "Pick the moment" and is outlined until the user nudges it, picks one of the other moments, or presses "This one" under the frame. "Other moments" shows each `climax.candidates` frame as a small picture with its time; choosing one makes it the key moment, which can then be nudged.
- If nobody answers for ten minutes the run stays waiting. It is not approved automatically.

## 4. Result

```
+------------------------------------------------------------------------------+
|  << EPIC REPLAY                                                              |
+------------------------------------------------------------------------------+
|  YOUR REPLAY                                        TRY ANOTHER NARRATOR     |
|  [o Football commentator] [o News reporter making]  +---------------------+  |
|  +----------------------------------------------+   | o Nature documentary|  |
|  |                                              |   |   A hushed narrator |  |
|  |                    ( > )                     |   |   observes the scene|  |
|  |  [TOP-CORNER FREE KICK]                      |   +---------------------+  |
|  |                                  0:00 / 0:29 |   Same moment, new voice.  |
|  +----------------------------------------------+   No need to confirm again.|
|  [ DOWNLOAD ]                    [AI COMMENTARY]    -----------------------  |
|  ------------------------------------------------   [ Start over with a    ] |
|  > What we saw                                      [ new clip             ] |
|  ------------------------------------------------   [ ] Keep for the demo    |
|  v The script                                       Delete this clip now     |
|    1.3 s · opening      Free kick, just outside ...                          |
|    11.4 s · key moment  TOP CORNER! What a strike!                           |
|    ...                                                                       |
|    [ Write it again ]   v Every line passed its checks.                      |
|  ------------------------------------------------                            |
|  > Time and cost                                                             |
+------------------------------------------------------------------------------+
```

Order on a phone: headline, tabs, player, download, the three panels, "Try another narrator", start over, keep, delete.

- The video does not play by itself, because it needs sound. The player shows a large play button over the first frame, which is the title card.
- "Try another narrator" lists the styles not yet made for this clip. Choosing one calls `POST /runs/{id}/restyle`, adds a tab marked "making", and shows a short progress list from step 5 on, in which step 6 only redraws the graphics. The current video keeps playing. There is no second confirmation (FR-042), and the line under the list says so.
- Tabs switch between versions of the same play. Each tab carries a dot in its narrator's colour, and each version has its own download.
- "The script" is open when the result first appears. It shows each line with the time it is spoken and its role, the two key lines marked. Under the lines, "Every line passed its checks.", or for a line that was replaced, the reason a check rejected the earlier draft. "Write it again" re-runs from S5 in the same style.
- "What we saw" shows the play sheet as on the play card, read-only.
- "Time and cost" shows the duration and cost of each step and the total.
- "Delete this clip now" removes every version and the provider-side copy at once. "Keep for the demo", off by default, exempts the run from the seven-day purge.
- In demo mode a band across the top reads "Cached run from <date>" (FR-038).

## Stopped

The Progress layout, with the message in place of the time left and one button for the action, by error code (contracts/http-api.md):

| Code | Message | Action |
|------|---------|--------|
| `invalid_upload` | "That clip is 42 seconds long. We need 3 to 30 seconds." (the actual reason) | Choose another clip |
| `sensitive_content` | "This clip isn't one we can narrate." followed by the reason in a few words: someone may be hurt or upset, or someone is undressed | Choose another clip |
| `cap_exceeded` | "We've reached today's limit for this demo." | none |
| `stage_failed` | "Something broke while <step name>. Your clip is safe." | Try again from that step |

## Wording

All text on the page lives in one file, `web/copy.py`, so wording can be reviewed in one place. Text that comes from a style pack (name, description, sample line) is not in it.

| Where | Text |
|-------|------|
| Header | Epic Replay |
| Start headline | Film it. We'll call it. |
| Drop area | Drop your clip here, or choose a file. / Any great moment. / Choose a file / 3 to 30 seconds · MP4, MOV or WebM · up to 200 MB / Change |
| Tip | Tip: film in slo-mo mode for a sharper replay. |
| Narrators | Who's narrating? / Listen |
| Optional | Optional / Who's the star? / Remove the clip's own sound |
| Consent | I filmed this or have permission to use it. Everyone in it is an adult man who is happy to be filmed. I have read the note below. |
| Provider line | Still frames from the clip are sent to <service> (<country>) to be looked at. Nothing else leaves this machine. <service> may use what it receives to train its models. |
| Go | Make my replay |
| Progress | Making your replay / Step N of 8 / Working / Show details / Hide details |
| Time left | A few seconds to go. / About half a minute to go. / About a minute to go. |
| Confirm | Your turn. / Is this what happened? / 10 back / Earlier / Later / 10 on / Frame N · T s / Pick the moment / This one / Other moments / Check this / Keep / Remove / Yes, that's it / Fix something / Not at all / The rest will cost about $X. |
| Result | Your replay / making / Download / AI commentary / Try another narrator / Same moment, new voice. No need to confirm again. |
| Panels | What we saw / The script / Write it again / Every line passed its checks. / Time and cost |
| End of page | Start over with a new clip / Keep for the demo / Delete this clip now |

## Not in the MVP page

Accounts, a gallery of past runs, share links, editing single script lines, vertical export, a language switch, score and team fields.

## Build notes

- Server-rendered template with HTMX for the partial updates and plain JavaScript for the file preview, the sample player, the narrator colour, the frame nudge and the event stream. No build step.
  As built (add-back 9): no HTMX. On each stage event the page fetches `GET /r/{id}?part=main` and swaps the server-rendered part in place with `fetch`; the script never builds HTML from data. One connection at a time: the event stream, or, after it drops, polling every two seconds with a new stream tried every 30 s. Focus after a swap: the same state returns focus to the element with the same id; a new state moves it to that state's heading, announced by a polite live region; with nothing focused in the page's live part it is left alone. "Show details" is kept per tab (session storage) across swaps and reloads.
- One stylesheet with the tokens of design.md as custom properties; the layout uses wrapping rows and no media queries.
- Keyboard: every control reachable by Tab; the style cards are a radio group with arrow-key movement; the frame nudge responds to left and right arrows when focused, and to Shift with left and right for ten frames.
- Colour is never the only signal: step states differ in mark and wording, and highlighted play-card items carry the words "Check this".
