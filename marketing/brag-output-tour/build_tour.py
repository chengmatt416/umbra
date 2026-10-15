#!/usr/bin/env python3
"""Writes composition/index.html for the /brag feature tour: every feature of Umbra, one scene each, on a 6-beat grid (3.3 s at 109 BPM).
Hyperframes needs the timed clips (scenes, videos, audio) in the HTML, so they are generated here; the animation is a loop in the page script.
    python3 build_tour.py && cd composition && npx hyperframes check"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SL = 3.3                    # one feature: 6 beats at 109.09 BPM
HOOK = 4.4                  # 8 beats
N = 13
PUNCH0 = HOOK + N * SL      # 47.3
LOCK0 = PUNCH0 + 4.4        # 51.7
TOTAL = LOCK0 + 3.3         # 55.0
FLIP = HOOK + 9 * SL        # 34.1: the dark colour begins with the approval

scenes = "\n".join(
    f'      <section id="sc{i + 1}" class="clip scene" data-start="{HOOK + i * SL:.2f}" data-duration="{SL + 0.05:.2f}" data-track-index="1">'
    f'<div class="ghost" id="g{i + 1}" data-layout-ignore></div><div class="txt"><div class="kick" id="k{i + 1}"></div><div class="title" id="t{i + 1}"></div><div class="desc" id="d{i + 1}"></div></div></section>'
    for i in range(N)
)
videos = "\n".join(
    f'          <div class="vw" id="vw{i + 1}"><video id="v{i + 1}" src="assets/video/f{i + 1:02d}.mp4" muted playsinline data-start="{HOOK + i * SL - 0.3:.2f}" data-duration="4.0" data-track-index="{2 + i}"></video></div>'
    for i in range(N)
)
dots = "".join(f'<i id="r{i + 1}"></i>' for i in range(N))
audio = [
    ("music", "assets/music/umbra-tour-bed.wav", 0, TOTAL, 1.0),
    ("sfx-rise", "assets/sfx/card-slide-1.ogg", HOOK - 0.45, 0.6, 0.3),
]
for i in range(N):
    audio.append((f"sfx-s{i + 1}", "assets/sfx/card-slide-1.ogg", HOOK + i * SL - 0.05, 0.6, 0.2))
audio += [
    ("sfx-flip", "assets/sfx/impactSoft_medium_002.ogg", FLIP - 0.55, 0.14, 0.5),
    ("sfx-approve", "assets/sfx/click_002.ogg", FLIP + 1.6, 0.01, 0.55),
    ("sfx-punch", "assets/sfx/bong_001.ogg", PUNCH0 + 0.2, 0.13, 0.5),
    ("sfx-lock", "assets/sfx/impactSoft_medium_001.ogg", LOCK0 + 0.1, 0.19, 0.5),
    ("sfx-bell", "assets/sfx/impactBell_heavy_000.ogg", LOCK0 + 0.55, 1.48, 0.38),
]
audio_html = "\n".join(
    f'      <audio id="{a}" src="{s}" data-start="{t:.2f}" data-duration="{d:.2f}" data-track-index="{40 + k}" data-volume="{v}"></audio>' for k, (a, s, t, d, v) in enumerate(audio)
)

html = r'''<!doctype html>
<html lang="en" data-resolution="portrait"
  data-composition-variables='[{"id":"lang","type":"enum","label":"Language","default":"en","options":[{"value":"en","label":"English"},{"value":"zh","label":"繁體中文"}]}]'>
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=1080, height=1920" />
<script src="https://cdn.jsdelivr.net/npm/gsap@3.14.2/dist/gsap.min.js"></script>
<script src="assets/audio-lite.js"></script>
<style>
  :root { --ivory:#e9ede5; --ink:#32443a; --accent:#66866d; --deep:#476d54; --night:#26372f; --card:#f7faf3; --line:rgba(102,134,109,.4);
    --serif:"Noto Serif","Noto Serif TC",Georgia,serif; --sans:"Roboto","Noto Sans TC",system-ui,sans-serif; --label:"Montserrat","Noto Sans TC",system-ui,sans-serif;
    --fg:#32443a; --fg2:#5d7461; --kc:#66866d; --dot:rgba(102,134,109,.38); --dotA:#476d54; }
  * { margin:0; padding:0; box-sizing:border-box; }
  html, body { width:1080px; height:1920px; overflow:hidden; background:var(--ivory); }
  #root { position:relative; width:100%; height:100%; overflow:hidden; font-family:var(--sans); color:var(--ink); }
  #bg { position:absolute; inset:0; background:var(--ivory); }
  #night { position:absolute; inset:0; background:var(--night); clip-path:circle(0px at 540px 1500px); }
  #glow { position:absolute; left:380px; top:-340px; width:1400px; height:1400px; border-radius:50%;
    background:radial-gradient(circle, rgba(143,176,151,.55) 0%, rgba(197,215,194,.28) 38%, rgba(233,237,229,0) 68%); }
  #glow2 { position:absolute; left:-620px; top:1180px; width:1200px; height:1200px; border-radius:50%;
    background:radial-gradient(circle, rgba(197,215,194,.5) 0%, rgba(233,237,229,0) 66%); }
  .ring { position:absolute; border-radius:50%; border:3px solid var(--line); }
  #ring1 { left:140px; top:620px; width:800px; height:800px; } #ring2 { left:-60px; top:420px; width:1200px; height:1200px; opacity:.6; }
  .clip { position:absolute; inset:0; }
  .scene { z-index:30; }
  .ghost { position:absolute; right:60px; top:150px; font:700 520px/1 var(--serif); letter-spacing:-.04em; color:var(--accent); opacity:0; }
  .txt { position:absolute; left:96px; top:236px; width:888px; }
  .kick { font:700 27px var(--label); letter-spacing:.2em; text-transform:uppercase; color:var(--kc); margin-bottom:22px; }
  .title { font-family:var(--serif); font-weight:700; letter-spacing:-.02em; color:var(--fg); }
  .mask { display:block; overflow:hidden; padding-bottom:.12em; } .mask > span { display:block; } .acc { color:var(--accc,#476d54); }
  .desc { font:500 38px/1.38 var(--sans); color:var(--fg2); margin-top:22px; max-width:860px; }
  #phone { position:absolute; left:220px; top:780px; width:640px; height:1420px; border-radius:84px; background:#17211c; opacity:0; z-index:20;
    box-shadow:0 60px 120px -40px rgba(25,45,33,.6); }
  #screen { position:absolute; left:18px; top:18px; width:604px; height:1384px; border-radius:68px; overflow:hidden; background:#e9ede5; }
  .vw { position:absolute; left:0; top:0; width:604px; height:1384px; opacity:0; }
  .vw video { position:absolute; left:0; top:0; width:604px; height:1384px; object-fit:cover; }
  #rail { position:absolute; left:96px; top:716px; display:flex; gap:12px; align-items:center; height:24px; z-index:25; opacity:0; }
  #rail i { display:block; width:18px; height:18px; border-radius:9px; background:var(--dot); }
  #hook1 { position:absolute; left:96px; top:640px; font:700 112px/1.04 var(--serif); letter-spacing:-.02em; color:var(--ink); z-index:30; }
  #hookk { position:absolute; left:96px; top:556px; font:700 28px var(--label); letter-spacing:.26em; text-transform:uppercase; color:var(--accent); z-index:30; }
  #hmark { position:absolute; left:330px; top:1180px; width:640px; height:640px; opacity:0; }
  #hook2 { position:absolute; left:96px; top:1010px; width:888px; font:500 52px/1.35 var(--serif); color:var(--fg2); z-index:30; }
  .punch { z-index:35; }
  #p1 { position:absolute; left:96px; top:520px; font:700 124px/1.05 var(--serif); letter-spacing:-.02em; color:#f1f6ee; }
  #p2 { position:absolute; left:96px; top:900px; width:888px; font:500 42px/1.5 var(--label); color:#b5cdb9; }
  #ghostBtn { position:absolute; left:96px; top:1240px; height:104px; width:430px; border-radius:52px; border:4px dashed rgba(197,168,120,.7); display:flex; align-items:center; justify-content:center;
    font:700 38px var(--sans); color:rgba(214,186,140,.85); }
  #ghostNote { position:absolute; left:96px; top:1366px; font:500 34px var(--label); color:#9db9a3; }
  #s5bg { position:absolute; inset:0; background:var(--ivory); opacity:0; }
  #mark { position:absolute; left:400px; top:420px; width:280px; height:280px; }
  #name { position:absolute; left:0; width:1080px; top:740px; text-align:center; font:700 230px/1 var(--serif); letter-spacing:-.03em; color:var(--ink); }
  #tagl { position:absolute; left:90px; width:900px; top:1010px; text-align:center; font:500 46px/1.35 var(--serif); color:#5d7461; }
  #free { position:absolute; left:0; width:1080px; top:1180px; text-align:center; font:700 34px var(--label); letter-spacing:.12em; text-transform:uppercase; color:var(--deep); }
  #url { position:absolute; left:180px; width:720px; top:1290px; height:120px; border-radius:60px; background:var(--deep); color:#f7fbf2; display:flex; align-items:center; justify-content:center; font:600 44px var(--label); }
</style>
</head>
<body>
<div id="root" data-composition-id="main" data-start="0" data-duration="__TOTAL__" data-width="1080" data-height="1920">
  <div id="bg"></div>
  <div id="night"></div>
  <div id="glow"></div><div id="glow2"></div>
  <div id="ring1" class="ring"></div><div id="ring2" class="ring"></div>

  <section id="hook" class="clip" data-start="0" data-duration="__HOOKD__" data-track-index="1">
    <svg id="hmark" data-layout-ignore viewBox="0 0 32 32"><defs><linearGradient id="hg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#8fa88f"/><stop offset="1" stop-color="#476d54"/></linearGradient></defs><circle cx="16" cy="16" r="14" fill="url(#hg)"/><circle cx="21.5" cy="12" r="10.5" fill="#e9ede5"/></svg><div id="hookk"></div><div id="hook1"></div><div id="hook2"></div>
  </section>

__SCENES__

  <div id="rail">__DOTS__</div>
  <div id="phone" data-layout-allow-overflow>
    <div id="screen">
__VIDEOS__
    </div>
  </div>

  <section id="punch" class="clip punch" data-start="__PUNCH0__" data-duration="4.5" data-track-index="1">
    <div id="p1"></div><div id="p2"></div><div id="ghostBtn"><span>Approve once</span></div><div id="ghostNote"></div>
  </section>

  <section id="lock" class="clip" data-start="__LOCK0__" data-duration="3.3" data-track-index="1" style="z-index:50">
    <div id="s5bg"></div>
    <svg id="mark" viewBox="0 0 32 32"><defs><linearGradient id="mg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#8fa88f"/><stop offset="1" stop-color="#476d54"/></linearGradient></defs><circle cx="16" cy="16" r="14" fill="url(#mg)"/><circle cx="21.5" cy="12" r="10.5" fill="#e9ede5"/></svg>
    <div id="name">Umbra</div><div id="tagl"></div><div id="free"></div><div id="url">umbra-agent.pages.dev</div>
  </section>

__AUDIO__
</div>
<script>
var V = (window.__hyperframes && window.__hyperframes.getVariables && window.__hyperframes.getVariables()) || {};
var Z = V.lang === "zh";
var SL = __SL__, HOOK = __HOOK__, N = __N__, PUNCH0 = __PUNCH0__, LOCK0 = __LOCK0__, FLIP = __FLIP__, TOTAL = __TOTAL__;
var PARTS = Z ? ["把事做完", "持續運作", "全程掌控"] : ["Do the work", "Keep it running", "Stay in control"];
var PART_OF = [0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2];
var F = Z ? [
  [["一句話，", "交給它。"], "打字或說話，它就變成一個工作階段。"],
  [["它替你", "操作手機。"], "讀畫面、點擊、輸入，再確認結果。"],
  [["你不看，", "它照做。"], "App 跑在看不見的背景顯示器。"],
  [["口袋裡的", "Linux。"], "Debian、bash、git、Python、Bun。"],
  [["每個 App", "上方的膠囊。"], "圈起螢幕，直接問它。"],
  [["能安全", "演進的計畫。"], "階段 DAG、代理樹、等待圖。"],
  [["手機滿了，", "就排隊。"], "公平、看得見的佇列，不是當機。"],
  [["暫停、繼續，", "當機也撐得住。"], "工作階段比一個行程活得更久。"],
  [["你的電腦，", "也是節點。"], "配對 Mac 或 Linux，隨時可撤銷。"],
  [["有風險，", "先問你。"], "付款、訊息、刪除：由你決定。"],
  [["有權限，", "不露祕密。"], "權杖留在保險庫，不交給代理。"],
  [["可查證的", "證據。"], "雜湊鏈日誌，與緊急輸入封鎖。"],
  [["隨時收回", "全部權限。"], "停止一切、撤銷輔助程式、刪除紀錄。"],
] : [
  [["Say it once."], "Type or speak it. It becomes a session."],
  [["It operates", "your phone."], "Reads the screen, taps, types, then checks."],
  [["Works while", "you don't watch."], "Apps run on an invisible display."],
  [["Linux in", "your pocket."], "Debian with bash, git, Python, Bun."],
  [["A capsule over", "every app."], "Circle the screen. Ask about it."],
  [["Plans that evolve", "safely."], "Phase DAG, agent tree, wait graph."],
  [["A queue,", "not a crash."], "When the phone is full, work waits in line."],
  [["Pause. Resume.", "Survive."], "Sessions outlive the app and the agent."],
  [["Your computers,", "as nodes."], "Pair a Mac or Linux box. Revoke any time."],
  [["It asks before", "anything risky."], "Payments, messages, deletions: your call."],
  [["Authority,", "not secrets."], "Tokens stay in the vault, never with the agent."],
  [["Evidence you", "can check."], "Hash-chained journals. Emergency lockdown."],
  [["Take it all", "back."], "Stop everything, revoke helpers, delete history."],
];
var HOOKT = Z ? { k: "UMBRA · ANDROID", l: ["你把手機", "交給了 AI。"], s: "它能做的事，一次看完。" } : { k: "UMBRA · FOR ANDROID", l: ["You gave an AI", "your phone."], s: "Here is everything it does." };
var PUNCHT = Z ? { l: ["它不能替自己", "核准請求。"], s: "不能操作自己的 App，<br>也不能回答自己的核准。", g: "只有你能按下這顆。" } : { l: ["It can't approve", "its own requests."], s: "No operating its own app.<br>No answering its own approvals.", g: "Only you can press this." };

function lines(el, arr, size, lh, accent) {
  el.style.fontSize = size + "px"; el.style.lineHeight = lh;
  el.innerHTML = arr.map(function (t, i) { return '<span class="mask"><span' + (accent && i === arr.length - 1 && arr.length > 1 ? ' class="acc"' : "") + ">" + t + "</span></span>"; }).join("");
}
function pad2(n) { return (n < 10 ? "0" : "") + n; }
var $ = function (s) { return document.querySelector(s); };

document.getElementById("hookk").textContent = HOOKT.k;
lines(document.getElementById("hook1"), HOOKT.l, Z ? 150 : 108, 1.04, true);
document.getElementById("hook2").textContent = HOOKT.s;
for (var i = 0; i < N; i++) {
  var n = i + 1, f = F[i];
  document.getElementById("k" + n).textContent = PARTS[PART_OF[i]] + " · " + pad2(n) + " / " + N;
  var one = f[0].length === 1;
  lines(document.getElementById("t" + n), f[0], Z ? 110 : (one ? 118 : 100), 1.06, true);
  document.getElementById("d" + n).textContent = f[1];
  document.getElementById("g" + n).textContent = pad2(n);
}
lines(document.getElementById("p1"), PUNCHT.l, Z ? 140 : 118, 1.06, false);
document.getElementById("p2").innerHTML = PUNCHT.s;
document.getElementById("ghostNote").textContent = PUNCHT.g;
document.getElementById("tagl").textContent = Z ? "住在你 Android 手機裡的自主代理" : "An autonomous agent that lives on your Android phone.";
document.getElementById("free").textContent = Z ? "免費開源 · Android 16" : "Free · open source · Android 16";
if (Z) { document.getElementById("tagl").style.fontSize = "52px"; document.getElementById("name").style.fontSize = "250px"; }

var tl = gsap.timeline({ paused: true });
/* ---- ambient depth: rings drift; the glows breathe with the bed (pre-extracted bands) ---- */
tl.to("#ring1", { rotation: 50, scale: 1.08, duration: TOTAL, ease: "none" }, 0);
tl.to("#ring2", { rotation: -36, scale: 0.95, duration: TOTAL, ease: "none" }, 0);
var A = window.AUDIO_LITE;
for (var fr = 0; fr < A.n; fr++) {
  tl.set("#glow", { opacity: 0.55 + 0.45 * A.bass[fr], scale: 1 + 0.07 * A.bass[fr] }, fr / A.fps);
  tl.set("#glow2", { opacity: 0.55 + 0.45 * A.treble[fr] }, fr / A.fps);
}
/* ---- hook (0-4.4) ---- */
tl.fromTo("#hookk", { opacity: 0, x: -30 }, { opacity: 1, x: 0, duration: 0.5, ease: "power3.out" }, 0.15);
tl.fromTo("#hook1 .mask > span", { yPercent: 115 }, { yPercent: 0, duration: 0.8, ease: "expo.out", stagger: 0.14 }, 0.3);
tl.fromTo("#hook2", { opacity: 0, y: 30 }, { opacity: 1, y: 0, duration: 0.6, ease: "power2.out" }, 2.1);
tl.fromTo("#hmark", { opacity: 0, rotation: -40, scale: 0.8 }, { opacity: 0.55, rotation: 0, scale: 1, duration: 1.4, ease: "expo.out" }, 0.5);
tl.to("#hmark", { opacity: 0, y: 60, duration: 0.5, ease: "power2.in" }, 3.85);
tl.to(["#hook1 .mask > span"], { yPercent: -115, duration: 0.45, ease: "power3.in", stagger: 0.07 }, 3.75);
tl.to(["#hookk", "#hook2"], { opacity: 0, duration: 0.35 }, 3.8);
/* ---- the phone rises with the first feature ---- */
tl.fromTo("#phone", { y: 1100, opacity: 1, scale: 0.94 }, { y: 0, opacity: 1, scale: 1, duration: 1.0, ease: "power3.out" }, HOOK - 0.55);
tl.fromTo("#rail", { opacity: 0, y: 12 }, { opacity: 1, y: 0, duration: 0.5, ease: "power2.out" }, HOOK + 0.2);
/* ---- thirteen features: one scene each ---- */
for (var i = 0; i < N; i++) {
  (function (i) {
    var n = i + 1, s = HOOK + i * SL, late = i === 9 ? 0.3 : 0;
    tl.fromTo("#k" + n, { opacity: 0, x: -24 }, { opacity: 1, x: 0, duration: 0.45, ease: "power3.out" }, s + 0.05 + late);
    tl.fromTo("#t" + n + " .mask > span", { yPercent: 115 }, { yPercent: 0, duration: 0.7, ease: "expo.out", stagger: 0.1 }, s + 0.1 + late);
    tl.fromTo("#d" + n, { opacity: 0, y: 22 }, { opacity: 1, y: 0, duration: 0.5, ease: "power2.out" }, s + 0.4 + late);
    if (i < 9) {
      tl.fromTo("#g" + n, { opacity: 0, scale: 0.9, x: 40 }, { opacity: 0.13, scale: 1, x: 0, duration: 0.8, ease: "power3.out" }, s + 0.05 + late);
      tl.to("#g" + n, { opacity: 0, duration: 0.3, ease: "power1.in" }, s + SL - 0.38);
    }
    tl.to(["#k" + n, "#d" + n], { opacity: 0, duration: 0.3, ease: "power1.in" }, s + SL - 0.38);
    tl.to("#t" + n + " .mask > span", { yPercent: -115, duration: 0.34, ease: "power3.in", stagger: 0.05 }, s + SL - 0.42);
    /* the screen: cross-dissolve to this feature's recording */
    tl.to("#vw" + n, { opacity: 1, duration: 0.5, ease: "power1.inOut" }, s - 0.22);
    if (i < N - 1) tl.to("#vw" + n, { opacity: 0, duration: 0.5, ease: "none" }, s + SL + 0.2);
    /* the rail */
    tl.to("#r" + n, { width: 54, backgroundColor: "#476d54", duration: 0.4, ease: "power2.out" }, s);
    if (i < N - 1) tl.to("#r" + n, { width: 18, backgroundColor: i >= 9 ? "#8fb097" : "rgba(102,134,109,0.38)", duration: 0.3 }, s + SL - 0.1);
    /* a slow drift so the phone is never dead still while something is being said */
    tl.fromTo("#phone", { scale: 1.0 }, { scale: 1.014, duration: SL, ease: "sine.inOut" }, s);
  })(i);
}
/* ---- act 3: the dark colour begins with the approval ---- */
tl.to("#night", { clipPath: "circle(2200px at 540px 1500px)", duration: 0.85, ease: "expo.inOut" }, FLIP - 0.6);
for (var j = 9; j < N; j++) {
  var t0 = HOOK + j * SL;
  tl.set("#t" + (j + 1), { "--fg": "#f1f6ee" }, t0 - 0.7);
  tl.set("#d" + (j + 1), { "--fg2": "#b5cdb9" }, t0 - 0.7);
  tl.set("#k" + (j + 1), { "--kc": "#8fb097" }, t0 - 0.7);
  tl.set("#t" + (j + 1), { "--accc": "#9cc4a6" }, t0 - 0.7);
}
tl.set("#rail", { "--dot": "rgba(143,176,151,.4)" }, FLIP - 0.5);
for (var q = 1; q <= 9; q++) tl.set("#r" + q, { backgroundColor: "rgba(143,176,151,0.4)" }, FLIP - 0.4);
/* ---- punchline (47.3-51.7) ---- */
tl.to("#phone", { y: 1500, duration: 0.8, ease: "power3.in" }, PUNCH0 - 0.35);
tl.to("#rail", { opacity: 0, duration: 0.3 }, PUNCH0 - 0.4);
tl.fromTo("#p1 .mask > span", { yPercent: 115 }, { yPercent: 0, duration: 0.8, ease: "expo.out", stagger: 0.14 }, PUNCH0 + 0.35);
tl.fromTo("#p2", { opacity: 0, y: 24 }, { opacity: 1, y: 0, duration: 0.6, ease: "power2.out" }, PUNCH0 + 1.35);
tl.fromTo("#ghostBtn", { opacity: 0, y: 30 }, { opacity: 1, y: 0, duration: 0.5, ease: "power2.out" }, PUNCH0 + 2.2);
tl.fromTo("#ghostNote", { opacity: 0 }, { opacity: 1, duration: 0.5 }, PUNCH0 + 2.6);
tl.to("#ghostBtn", { x: 6, duration: 0.07, repeat: 5, yoyo: true, ease: "none" }, PUNCH0 + 3.0);
/* ---- lockup (51.7-55.0) ---- */
tl.to("#s5bg", { opacity: 1, duration: 0.8, ease: "none" }, LOCK0);
tl.fromTo("#mark", { scale: 0.6, opacity: 0, rotation: -40 }, { scale: 1, opacity: 1, rotation: 0, duration: 0.9, ease: "expo.out" }, LOCK0 + 0.4);
tl.fromTo("#name", { opacity: 0, y: 30 }, { opacity: 1, y: 0, duration: 0.7, ease: "power3.out" }, LOCK0 + 0.55);
tl.fromTo("#tagl", { opacity: 0, y: 20 }, { opacity: 1, y: 0, duration: 0.6, ease: "power2.out" }, LOCK0 + 1.05);
tl.fromTo("#free", { opacity: 0 }, { opacity: 1, duration: 0.5 }, LOCK0 + 1.45);
tl.fromTo("#url", { opacity: 0, scale: 0.92 }, { opacity: 1, scale: 1, duration: 0.5, ease: "back.out(2)" }, LOCK0 + 1.75);
window.__timelines["main"] = tl;
tl.seek(0);
</script>
</body>
</html>
'''
for k, v in {"__TOTAL__": f"{TOTAL:.1f}", "__HOOKD__": f"{HOOK + 0.05:.2f}", "__SCENES__": scenes, "__DOTS__": dots, "__VIDEOS__": videos, "__PUNCH0__": f"{PUNCH0:.2f}",
             "__LOCK0__": f"{LOCK0:.2f}", "__AUDIO__": audio_html, "__SL__": str(SL), "__HOOK__": str(HOOK), "__N__": str(N), "__FLIP__": f"{FLIP:.2f}"}.items():
    html = html.replace(k, v)
open(os.path.join(HERE, "composition", "index.html"), "w").write(html)
print("wrote composition/index.html · total %.1f s · %d features" % (TOTAL, N))
