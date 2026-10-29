// The page's script (contracts/ui.md): the file drop with its browser-side checks and preview,
// the upload, the elapsed time, and progress by polling GET /runs/{id} every two seconds. The
// page reloads when the run changes, so the server renders each state; the play card's own
// script is in _card.html.
(() => {
  const $ = (id) => document.getElementById(id);
  const run = window.RUN;
  // the message of an error answer, or `fallback` when the answer is not the API's JSON
  async function why(r, fallback) {
    try { return (await r.json()).error.message || fallback; } catch (err) { return fallback; }
  }

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

  // ---- Delete this clip now (FR-037): at once, then back to the start
  document.querySelectorAll("[data-delete]").forEach((b) => b.addEventListener("click", async () => {
    b.disabled = true;
    try {
      const r = await fetch(`/runs/${b.dataset.delete}`, { method: "DELETE" });
      if (r.ok || r.status === 404) { location.href = "/"; return; }
      $("delete-error").textContent = await why(r, "The clip was not deleted. Try again.");
    } catch (err) {
      $("delete-error").textContent = "The clip was not deleted. Try again.";
    }
    b.disabled = false;
  }));

  // ---- 2 and 3. Progress and confirm: elapsed time, and polling
  if (run && (run.status === "running" || run.status === "waiting_play_card")) {
    const started = Date.parse(run.created_at), skew = Date.now() - Date.parse(run.now);
    const tick = () => {
      const s = Math.max(0, Math.floor((Date.now() - skew - started) / 1000));
      if ($("elapsed")) $("elapsed").textContent = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
    };
    tick();
    setInterval(tick, 1000);
    const sig = (r) => r.status + "|" + r.steps.map((s) => s.status).join(",");
    const seen = sig(run);
    setInterval(async () => {
      try {
        const r = await fetch(`/runs/${run.run_id}`);
        if (r.status === 404) { location.href = "/"; return; }  // deleted elsewhere
        if (r.ok && sig(await r.json()) !== seen) location.reload();
      } catch (err) { /* keep polling */ }
    }, 2000);
  }
})();
