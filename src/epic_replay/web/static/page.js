// The page's script (contracts/ui.md): the file drop with its browser-side checks and preview,
// the narrator cards, the upload, the elapsed time, the play card, the details view, and live
// progress. Progress comes by server-sent events (GET /runs/{id}/events); while the stream is
// down the page polls GET /runs/{id} every two seconds and tries the stream again every 30 s.
// On each change it fetches the part of the page the server renders for the run's state
// (GET /r/{id}?part=main) and swaps it in place, so every value on the page is escaped by the
// server's templates; this script never builds HTML from data.
(() => {
  const $ = (id) => document.getElementById(id);
  const json = (root, id) => { const el = root.querySelector(`#${id}`); return el ? JSON.parse(el.textContent) : null; };
  let run = json(document, "run-data");
  // the message of an error answer, or `fallback` when the answer is not the API's JSON
  async function why(r, fallback) {
    try { return (await r.json()).error.message || fallback; } catch (err) { return fallback; }
  }
  const store = {  // per-tab memory; the page works without it
    get(k) { try { return sessionStorage.getItem(k); } catch (err) { return null; } },
    set(k, v) { try { sessionStorage.setItem(k, v); } catch (err) { /* not kept */ } },
  };

  // ---- 1. Start
  const form = $("start");
  if (form) {
    const TYPES = [".mp4", ".mov", ".webm"], MAX_BYTES = 200 * 1024 * 1024, MIN_S = 3, MAX_S = 30;
    let file = null, fileOk = false;
    const ready = () => { $("go").disabled = !(fileOk && $("consent").checked); };

    function choose(f) {
      file = f; fileOk = false;
      $("file-error").textContent = "";
      const ext = f.name.slice(f.name.lastIndexOf(".")).toLowerCase();
      if (!TYPES.includes(ext)) return fail("The clip must be an MP4, MOV or WebM file.");
      if (f.size > MAX_BYTES) return fail("The clip is larger than 200 MB.");
      const v = $("preview");
      if (v.src) URL.revokeObjectURL(v.src);
      v.src = URL.createObjectURL(f);
      v.onloadedmetadata = () => {
        const d = v.duration;
        $("file-info").textContent = `${f.name} · ${d.toFixed(1)} s`;
        $("drop-empty").hidden = true;
        $("drop-file").hidden = false;
        if (d < MIN_S - 0.05 || d > MAX_S + 0.05) return fail(`The clip is ${d.toFixed(1)} s long; it must be 3 to 30 seconds.`);
        fileOk = true;
        ready();
      };
      v.onerror = () => fail("The file could not be read as a video.");
      ready();
    }
    function fail(msg) { fileOk = false; $("file-error").textContent = msg; ready(); }

    $("file").addEventListener("change", (e) => e.target.files[0] && choose(e.target.files[0]));
    $("change").addEventListener("click", () => $("file").click());
    $("consent").addEventListener("change", ready);
    const drop = $("drop");
    drop.addEventListener("dragover", (e) => { e.preventDefault(); drop.classList.add("over"); });
    drop.addEventListener("dragleave", () => drop.classList.remove("over"));
    drop.addEventListener("drop", (e) => {
      e.preventDefault(); drop.classList.remove("over");
      if (e.dataTransfer.files[0]) choose(e.dataTransfer.files[0]);
    });

    // the narrator cards: a radio group; choosing one recolours the page at once (design.md 6)
    const recolour = () => {
      const on = form.querySelector('input[name="style"]:checked');
      if (!on) return;
      const card = on.closest(".narrator"), root = document.documentElement.style;
      root.setProperty("--accent", card.dataset.accent);
      root.setProperty("--on-accent", card.dataset.onAccent);
      root.setProperty("--accent-text", card.dataset.accentText);
    };
    form.querySelectorAll('input[name="style"]').forEach((r) => r.addEventListener("change", recolour));
    recolour();

    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      if ($("go").disabled) return;
      $("go").disabled = true;
      $("go-error").textContent = "";
      const data = new FormData(form);
      data.set("file", file);
      data.set("consent", "true");
      data.set("keep_original_audio", $("mute").checked ? "false" : "true");
      try {
        const r = await fetch("/runs", { method: "POST", body: data });
        if (r.ok) { location.href = `/r/${(await r.json()).run_id}`; return; }
        $("go-error").textContent = await why(r, "Something went wrong on the server. Try again.");
      } catch (err) {
        $("go-error").textContent = "The upload did not reach the server. Try again.";
      }
      ready();
    });
  }

  // ---- 3. The play card (T014), inside step 4; run again after each swap
  function initCard(root) {
    const card = json(root, "card-data"), copy = json(root, "card-copy");
    if (!card) return;
    const fps = card.fps, last = card.last_frame;
    const mustCheck = card.play_sheet.sensitive.checked === false;
    let frame = card.climax.frame, answered = false, clipOk = null, busy = false;
    function show() {
      $("frame").src = card.frame_url.replace("{frame}", frame);
      $("where").textContent = copy.frame.replace("{frame}", frame).replace("{t}", (frame / fps).toFixed(2));
      $("key").classList.toggle("answered", answered);
      const ready = answered && (!mustCheck || clipOk === true);
      $("confirm").disabled = busy || !ready;
      $("why").textContent = !answered ? copy.why : mustCheck && clipOk !== true ? copy.why_check : "";
      root.querySelectorAll(".others button").forEach((b) =>
        b.classList.toggle("chosen", Number(b.dataset.frame) === frame));
      root.querySelectorAll("#clip-check .choice").forEach((b) =>
        b.classList.toggle("chosen", String(clipOk) === b.dataset.ok));
      if ($("clip-check")) $("clip-check").classList.toggle("answered", clipOk === true);
    }
    function move(step) { frame = Math.max(0, Math.min(last, frame + step)); answered = true; show(); }
    root.querySelectorAll("#nudge button").forEach((b) => {
      // a click moves one step; holding moves five steps a second after a short pause (ui.md)
      const step = Number(b.dataset.step);
      let wait = null, repeat = null, held = false;
      const stop = () => { clearTimeout(wait); clearInterval(repeat); };
      b.addEventListener("pointerdown", () => {
        held = false; stop();
        wait = setTimeout(() => { held = true; move(step); repeat = setInterval(() => move(step), 200); }, 400);
      });
      ["pointerup", "pointerleave", "pointercancel"].forEach((ev) => b.addEventListener(ev, stop));
      b.addEventListener("click", () => { if (!held) move(step); held = false; });
    });
    $("nudge").addEventListener("keydown", (e) => {
      if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
      e.preventDefault();
      move((e.key === "ArrowLeft" ? -1 : 1) * (e.shiftKey ? 10 : 1));
    });
    $("this-one").addEventListener("click", () => { answered = true; show(); });
    root.querySelectorAll(".others button").forEach((b) =>
      b.addEventListener("click", () => { frame = Number(b.dataset.frame); answered = true; show(); }));
    async function send(edits) {
      busy = true; show();
      try {
        const r = await fetch(`/runs/${card.run_id}/play-card`, {
          method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ action: "confirm", edits }),
        });
        // the confirm button was disabled while sending, which took the focus away; the
        // answer was given here, so the next state's heading takes it
        if (r.ok) { live.refresh(true); live.start(); return; }
        $("card-status").textContent = await why(r, copy.failed);
        if (r.status === 409) live.refresh();  // answered or deleted in another tab
      } catch (err) {
        $("card-status").textContent = copy.failed;
      }
      busy = false; show();
    }
    root.querySelectorAll("#clip-check .choice").forEach((b) => b.addEventListener("click", () => {
      clipOk = b.dataset.ok === "true";
      if (clipOk) show(); else send({ clip_ok: false });
    }));
    $("confirm").addEventListener("click", () => {
      const edits = { climax_frame: frame };
      if (mustCheck) edits.clip_ok = true;
      send(edits);
    });
    show();
  }

  // ---- the swapped part: delete, details, the card
  function initMain(root) {
    // Delete this clip now (FR-037): at once, then back to the start
    root.querySelectorAll("[data-delete]").forEach((b) => b.addEventListener("click", async () => {
      b.disabled = true;
      try {
        const r = await fetch(`/runs/${b.dataset.delete}`, { method: "DELETE" });
        if (r.ok || r.status === 404) { live.stop(); location.href = "/"; return; }
        $("delete-error").textContent = await why(r, "The clip was not deleted. Try again.");
      } catch (err) {
        $("delete-error").textContent = "The clip was not deleted. Try again.";
      }
      b.disabled = false;
    }));
    // Show details (FR-036): closed by default, kept for this tab across swaps and reloads
    const toggle = root.querySelector("#details-toggle");
    if (toggle) {
      const set = (open) => {
        $("steps-col").classList.toggle("details-open", open);
        toggle.setAttribute("aria-expanded", String(open));
        toggle.textContent = open ? toggle.dataset.hide : toggle.dataset.show;
      };
      set(store.get("details") === "open");
      toggle.addEventListener("click", () => {
        const open = toggle.getAttribute("aria-expanded") !== "true";
        store.set("details", open ? "open" : "closed");
        set(open);
      });
    }
    initCard(root);
  }

  // Put the server's new part in place. Focus: if the state is the same, back to the element
  // with the same id; if the state changed, to the new state's heading, announced in words;
  // if nothing in the page's live part had focus, left alone.
  function swap(html, acted) {
    const t = document.createElement("template");
    t.innerHTML = html;
    const main = $("main"), who = $("who"), was = run ? run.status : null;
    const active = document.activeElement;
    const inside = acted || (active && active !== document.body && (main.contains(active) || who.contains(active)));
    const focusId = inside ? active.id : null;
    main.replaceChildren(...t.content.querySelector("#main-part").childNodes);
    who.replaceChildren(...t.content.querySelector("#who-part").childNodes);
    run = json(main, "run-data");
    initMain(main);
    if (run && run.status !== was) {
      const h = main.querySelector("[data-heading]");
      if (h) {
        $("announce").textContent = h.textContent.trim();
        if (inside) h.focus();
      }
    } else if (focusId && $(focusId)) {
      $(focusId).focus();
    }
    if (!run || ["done", "stopped", "failed"].includes(run.status)) live.stop();
  }

  // ---- 2 and 3. Progress and confirm: one connection, by events or by polling, never both
  const live = (() => {
    let source = null, poller = null, retry = null, busy = false, again = false, acted = false, lastId = 0, over = false;
    const sig = (r) => r.status + "|" + r.steps.map((s) => s.status + ":" + s.duration_s).join(",");
    async function refresh(byUser = false) {
      if (!run) return;
      acted = acted || byUser;
      if (busy) { again = true; return; }
      busy = true;
      try {
        const r = await fetch(`/r/${run.run_id}?part=main`);
        if (r.status === 404) { stop(); location.href = "/"; return; }  // deleted elsewhere
        if (r.ok) { swap(await r.text(), acted); acted = false; }
      } catch (err) { /* the next event or poll tries again */ }
      busy = false;
      if (again) { again = false; refresh(); }
    }
    function open() {
      if (over || source) return;
      const es = new EventSource(`/runs/${run.run_id}/events?after=${lastId}`);
      source = es;
      es.onopen = () => { if (source === es) stopPolling(); };
      es.onmessage = (e) => { lastId = Number(e.lastEventId) || lastId; refresh(); };
      es.onerror = () => {
        // close it so the browser's own reconnect cannot race the poller; the poller asks
        // for the stream again every 30 s
        es.close();
        if (source === es) source = null;
        startPolling();
      };
    }
    async function poll() {
      try {
        const r = await fetch(`/runs/${run.run_id}`);
        if (r.status === 404) { stop(); location.href = "/"; return; }  // deleted elsewhere
        if (r.ok && sig(await r.json()) !== sig(run)) refresh();
      } catch (err) { /* keep polling */ }
    }
    function startPolling() {
      if (over || poller) return;
      poller = setInterval(poll, 2000);
      retry = setInterval(open, 30000);
      poll();
    }
    function stopPolling() {
      clearInterval(poller); clearInterval(retry);
      poller = retry = null;
    }
    function start() { over = false; if (!source && !poller) open(); }
    function stop() {
      over = true;
      if (source) { source.close(); source = null; }
      stopPolling();
    }
    window.addEventListener("pagehide", stop);
    return { refresh, start, stop };
  })();

  // the time since the run started, counted in the browser from the server's clock
  if (run && (run.status === "running" || run.status === "waiting_play_card")) {
    const started = Date.parse(run.created_at), skew = Date.now() - Date.parse(run.now);
    const tick = () => {
      const s = Math.max(0, Math.floor((Date.now() - skew - started) / 1000));
      if ($("elapsed")) $("elapsed").textContent = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
    };
    tick();
    setInterval(tick, 1000);
    live.start();
  }
  if ($("main")) initMain($("main"));
})();
