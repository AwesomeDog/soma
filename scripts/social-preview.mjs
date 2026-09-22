// Renders the GitHub social preview card (Settings -> General -> Social preview).
//
//   node scripts/social-preview.mjs
//
// Writes docs/img/social-preview.svg (editable source) and .png (1280x640, the
// file you upload). Deterministic: no network, no timestamps, no randomness.

import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const OUT_SVG = resolve(ROOT, "docs/img/social-preview.svg");
const OUT_PNG = resolve(ROOT, "docs/img/social-preview.png");
const ICON = resolve(ROOT, "docs/specs/icon.svg");

// The mark is docs/specs/icon.svg inlined verbatim — single source of truth, so
// the card can never drift from the icon again. Strip the outer <svg> wrapper
// and the comments; what is left is one <g> on the icon's own 1000-unit grid.
const MARK_SVG = readFileSync(ICON, "utf8")
  .replace(/<!--[\s\S]*?-->/g, "")
  .replace(/\n[ \t]*\n/g, "\n")
  .replace(/^[\s\S]*?<svg[^>]*>/, "")
  .replace(/<\/svg>\s*$/, "")
  .trim();

const W = 1280;
const H = 640;
const PAD = 88;

const C = {
  bg0: "#0a0d13",
  bg1: "#151b27",
  accent: "#0071e3",
  accentSoft: "#4da3ff",
  text: "#f5f7fa",
  muted: "#98a2b6",
  chip: "#1b2230",
  chipText: "#c8d2e4",
  card: "#0e1219",
  cardLine: "#ffffff",
  termText: "#e6ebf5",
  termDim: "#606b80",
};

const MARK = { size: 92, gap: 24 };

const COPY = {
  title: "Soma",
  lines: [
    "Search your files by meaning or exact keywords.",
    "PDFs, Office, EPUB, images, audio and video.",
  ],
  chips: ["ONE BINARY", "LOCAL MODELS", "CLI · WEB UI · API"],
  url: "github.com/AwesomeDog/soma",
};

// Terminal lines: segments drawn left-to-right, plus an optional right-aligned
// tail. Content mirrors the illustrative output in README's Quick Start.
const NBSP = " ";
const TERM = [
  [{ t: "$ ", f: C.accentSoft }, { t: "soma project add ~/notes", f: C.termText }],
  [{ t: "$ ", f: C.accentSoft }, { t: "soma sync", f: C.termText }],
  [{ t: "$ ", f: C.accentSoft }, { t: 'soma search "how does auth work" --limit 3', f: C.termText }],
  [],
  [
    { t: "1.  ", f: C.termDim },
    { t: "soma://notes/api/authentication.md", f: C.termText },
    { score: "0.91" },
  ],
  [{ t: `${NBSP}${NBSP}${NBSP}${NBSP}`, f: C.termDim }, { t: "Requests are authenticated at the gateway…", f: C.termDim }],
  [
    { t: "2.  ", f: C.termDim },
    { t: "soma://notes/runbooks/login.md", f: C.termText },
    { score: "0.84" },
  ],
  [
    { t: "3.  ", f: C.termDim },
    { t: "soma://notes/api/rate-limiter.md", f: C.termText },
    { score: "0.79" },
  ],
];

const CARD = { w: 544, h: 344, r: 14 };
CARD.x = W - PAD - CARD.w;
CARD.y = (H - CARD.h) / 2;

const esc = (s) =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

// --- text block -------------------------------------------------------------
// Left column is flush with the card: same top edge, same bottom edge, and the
// gaps between the four groups (mark+title / tagline / chips / url) are equal.
const LEFT_TOP = CARD.y;
const LEFT_BOTTOM = CARD.y + CARD.h;

// The icon's ink does not fill its 1000-unit grid: it spans y 104..896 once the
// strokes and terminal nodes are counted, so those margins have to come off
// before the mark can sit flush with the card's top edge. Keep in sync with
// docs/specs/icon.svg if the geometry ever moves.
const INK_TOP = 0.104;
const INK_BOTTOM = 0.896;
const GAP = 52; // edge-to-edge between the four groups

const markX = PAD;
const markY = LEFT_TOP - MARK.size * INK_TOP;
const markInkBottom = markY + MARK.size * INK_BOTTOM;
const titleX = markX + MARK.size + MARK.gap;
const titleBaseline = markY + MARK.size / 2 + 24; // 24 = half the cap height of 68px text

const lineY = [markInkBottom + GAP + 17, markInkBottom + GAP + 49];
const chipY = lineY[1] + 6 + GAP;
const chipH = 36;
const urlY = LEFT_BOTTOM - 5;
const CHIP_PX = 9.4; // approx advance width for 14px uppercase Helvetica + letter-spacing

const chips = COPY.chips.map((label) => ({ label, w: label.length * CHIP_PX + 34 }));

// --- terminal geometry ------------------------------------------------------
const barH = 40;
const termPad = 26;
const termSize = 17;
const termLead = 30;
const firstLineBaseline = CARD.y + barH + 34;

function termSvg() {
  let out = "";
  let y = firstLineBaseline;
  for (const line of TERM) {
    if (line.length === 0) {
      y += termLead * 0.6;
      continue;
    }
    let x = CARD.x + termPad;
    let score = null;
    for (const seg of line) {
      if (seg.score !== undefined) {
        score = seg.score;
        continue;
      }
      out += `<text x="${x}" y="${y}" font-family="Menlo, SFMono-Regular, Consolas, monospace" font-size="${termSize}" fill="${seg.f}">${esc(seg.t)}</text>`;
      x += seg.t.length * (termSize * 0.6);
    }
    if (score !== null) {
      out += `<text x="${CARD.x + CARD.w - termPad}" y="${y}" text-anchor="end" font-family="Menlo, SFMono-Regular, Consolas, monospace" font-size="${termSize}" fill="${C.accentSoft}">${esc(score)}</text>`;
    }
    y += termLead;
  }
  return out;
}

// --- the card ---------------------------------------------------------------
const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" role="img" aria-label="Soma — local semantic search over your own files">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="${C.bg0}"/>
      <stop offset="1" stop-color="${C.bg1}"/>
    </linearGradient>
    <radialGradient id="glowA">
      <stop offset="0" stop-color="${C.accent}" stop-opacity="0.42"/>
      <stop offset="1" stop-color="${C.accent}" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="glowB">
      <stop offset="0" stop-color="${C.accentSoft}" stop-opacity="0.16"/>
      <stop offset="1" stop-color="${C.accentSoft}" stop-opacity="0"/>
    </radialGradient>
    <filter id="shadow" x="-20%" y="-20%" width="140%" height="150%">
      <feDropShadow dx="0" dy="18" stdDeviation="24" flood-color="#000000" flood-opacity="0.55"/>
    </filter>
  </defs>

  <rect width="${W}" height="${H}" fill="url(#bg)"/>
  <ellipse cx="1150" cy="70" rx="430" ry="300" fill="url(#glowA)"/>
  <ellipse cx="120" cy="620" rx="360" ry="260" fill="url(#glowB)"/>

  <!-- mark: docs/specs/icon.svg (1000-unit grid, scaled) -->
  <g transform="translate(${markX} ${markY}) scale(${MARK.size / 1000})">
${MARK_SVG.replace(/^/gm, "    ")}
  </g>

  <text x="${titleX}" y="${titleBaseline}" font-family="Helvetica Neue, Helvetica, Arial, sans-serif" font-size="68" font-weight="700" fill="${C.text}" letter-spacing="-1">${esc(COPY.title)}</text>
${COPY.lines
  .map(
    (l, i) =>
      `  <text x="${PAD}" y="${lineY[i]}" font-family="Helvetica Neue, Helvetica, Arial, sans-serif" font-size="23" fill="${C.muted}">${esc(l)}</text>`
  )
  .join("\n")}
${chips
  .map((c, i) => {
    const x = PAD + chips.slice(0, i).reduce((a, b) => a + b.w + 10, 0);
    return `  <g><rect x="${x}" y="${chipY}" width="${c.w}" height="${chipH}" rx="${chipH / 2}" fill="${C.chip}" stroke="${C.cardLine}" stroke-opacity="0.10"/><text x="${x + c.w / 2}" y="${chipY + chipH / 2 + 5}" text-anchor="middle" font-family="Helvetica Neue, Helvetica, Arial, sans-serif" font-size="14" letter-spacing="1.1" fill="${C.chipText}">${esc(c.label)}</text></g>`;
  })
  .join("\n")}
  <text x="${PAD}" y="${urlY}" font-family="Helvetica Neue, Helvetica, Arial, sans-serif" font-size="19" letter-spacing="0.4" fill="${C.chipText}">${esc(COPY.url)}</text>

  <!-- terminal card -->
  <g filter="url(#shadow)">
    <rect x="${CARD.x}" y="${CARD.y}" width="${CARD.w}" height="${CARD.h}" rx="${CARD.r}" fill="${C.card}"/>
  </g>
  <rect x="${CARD.x}" y="${CARD.y}" width="${CARD.w}" height="${CARD.h}" rx="${CARD.r}" fill="none" stroke="${C.cardLine}" stroke-opacity="0.14"/>
  <path d="M${CARD.x} ${CARD.y + barH} h${CARD.w}" stroke="${C.cardLine}" stroke-opacity="0.10"/>
  <circle cx="${CARD.x + 24}" cy="${CARD.y + barH / 2}" r="5.5" fill="#ff5f57"/>
  <circle cx="${CARD.x + 44}" cy="${CARD.y + barH / 2}" r="5.5" fill="#febc2e"/>
  <circle cx="${CARD.x + 64}" cy="${CARD.y + barH / 2}" r="5.5" fill="#28c840"/>
  <text x="${CARD.x + CARD.w / 2}" y="${CARD.y + barH / 2 + 5}" text-anchor="middle" font-family="Menlo, SFMono-Regular, Consolas, monospace" font-size="14" fill="#5f6a7d">soma</text>
${termSvg()}
</svg>
`;

mkdirSync(dirname(OUT_SVG), { recursive: true });
writeFileSync(OUT_SVG, svg);

// Rasterise: librsvg first — ImageMagick's MSVG drops gradients, shadows and
// embedded images.
const renderers = [
  ["rsvg-convert", ["-w", String(W), "-h", String(H), "-o", OUT_PNG, OUT_SVG]],
  ["magick", ["-background", "none", OUT_SVG, "-resize", `${W}x${H}!`, OUT_PNG]],
  ["convert", ["-background", "none", OUT_SVG, "-resize", `${W}x${H}!`, OUT_PNG]],
];
let rendered = false;
for (const [bin, args] of renderers) {
  try {
    execFileSync(bin, args, { stdio: "pipe" });
    rendered = true;
    break;
  } catch (err) {
    if (err.code !== "ENOENT") throw err;
  }
}
if (!rendered) throw new Error("no SVG renderer found (need rsvg-convert, magick or convert)");

console.log(`svg  ${OUT_SVG}`);
console.log(`png  ${OUT_PNG}  ${(statSync(OUT_PNG).size / 1024).toFixed(0)} KB`);
