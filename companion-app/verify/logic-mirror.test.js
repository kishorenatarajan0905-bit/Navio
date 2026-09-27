const assert = require("assert");

// --- Mirror of GeoMath ---
function round6(v) { return Math.round(v * 1e6) / 1e6; }
function round1(v) { return Math.round(v * 10) / 10; }
function toRad(d) { return d * Math.PI / 180; }
function haversine(p1, p2) {
  const R = 6371000;
  const dLa = toRad(p2.lat - p1.lat), dLo = toRad(p2.lon - p1.lon);
  const a = Math.sin(dLa / 2) ** 2 + Math.cos(toRad(p1.lat)) * Math.cos(toRad(p2.lat)) * Math.sin(dLo / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}
function bearing(p1, p2) {
  const f1 = toRad(p1.lat), f2 = toRad(p2.lat), dl = toRad(p2.lon - p1.lon);
  const y = Math.sin(dl) * Math.cos(f2), x = Math.cos(f1) * Math.sin(f2) - Math.sin(f1) * Math.cos(f2) * Math.cos(dl);
  return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
}
function angleDiff(a, b) { return ((b - a + 540) % 360) - 180; }

// --- Mirror of RouteMath ---
function computeCum(c) { const a = [0]; for (let i = 1; i < c.length; i++) a.push(a[i - 1] + haversine(c[i - 1], c[i])); return a; }
function dedupe(p) { const o = []; for (const q of p) { const l = o[o.length - 1]; if (!l || l.lat !== q.lat || l.lon !== q.lon) o.push(q); } return o; }
function downsample(c, maxN) {
  const n = c.length;
  if (n <= maxN) return c.slice();
  const req = new Set([0, n - 1]);
  let iMinLa = 0, iMaxLa = 0, iMinLo = 0, iMaxLo = 0;
  for (let i = 1; i < n; i++) {
    if (c[i].lat < c[iMinLa].lat) iMinLa = i;
    if (c[i].lat > c[iMaxLa].lat) iMaxLa = i;
    if (c[i].lon < c[iMinLo].lon) iMinLo = i;
    if (c[i].lon > c[iMaxLo].lon) iMaxLo = i;
  }
  req.add(iMinLa); req.add(iMaxLa); req.add(iMinLo); req.add(iMaxLo);
  const extra = Math.max(1, maxN - req.size);
  const den = (extra - 1 === 0) ? 1 : extra - 1;
  for (let k = 0; k < extra; k++) req.add(Math.round(k * (n - 1) / den));
  return [...req].sort((a, b) => a - b).map(i => c[i]);
}
function snapIndex(coords, p) {
  let best = 0, bd = Infinity;
  for (let i = 0; i < coords.length; i++) { const d = haversine(coords[i], p); if (d < bd) { bd = d; best = i; } }
  return best;
}
function estimateDistanceToTurn(coords, cum, i) {
  if (coords.length < 3) return 100;
  let dist = 0;
  for (let k = i; k < coords.length - 2; k++) {
    dist += haversine(coords[k], coords[k + 1]);
    const d = Math.abs(angleDiff(bearing(coords[k], coords[k + 1]), bearing(coords[k + 1], coords[k + 2])));
    if (d > 30) return Math.max(20, Math.round(dist));
  }
  const remaining = (cum[cum.length - 1] - cum[i]) || 100;
  return Math.max(20, remaining <= 0 ? 100 : Math.round(remaining));
}
function estimateTurnType(coords, i) {
  if (coords.length < 3) return 0;
  for (let k = i; k < coords.length - 2; k++) {
    const d = angleDiff(bearing(coords[k], coords[k + 1]), bearing(coords[k + 1], coords[k + 2]));
    if (d < -150 || d > 150) return 5;
    if (d < -30) return 1;
    if (d > 30) return 2;
  }
  return 0;
}
function plannedSpeedKmh(coords, i) {
  if (coords.length < 3) return 18;
  let slow = 0;
  for (let k = i; k < Math.min(i + 6, coords.length - 2); k++) {
    const d = Math.abs(angleDiff(bearing(coords[k], coords[k + 1]), bearing(coords[k + 1], coords[k + 2])));
    if (d > 25) { slow = Math.min(1, (d - 25) / 60); break; }
  }
  return round1(22 - (22 - 9) * slow);
}
function sanitizeRoad(t) { const c = String(t || "").replace(/[^A-Za-z0-9 .,_-]/g, "").substring(0, 20).trim(); return c || "Route"; }

// --- Mirror of OrderParser ---
const DROP_KEYWORDS = ["delivering to", "deliver to", "delivery to", "deliver at", "drop off at", "dropoff at", "drop at", "drop:", "drop "];
const PICKUP_KEYWORDS = ["pickup at", "pick up at", "pick up from", "pickup from", "pickup:", "collect from", "restaurant:", "pickup"];
const PINCODE = /\b\d{6}\b/;
function clean(s) {
  let t = s.replace(/#\d+/g, " ");
  t = t.replace(/[^\p{L}\p{N}\s,()&/':.-]/gu, " ");
  t = t.replace(/\s+/g, " ").trim();
  t = t.replace(/^[ ,.:-]+|[ ,.:-]+$/g, "");
  return t.substring(0, 160).trim();
}
function parse(source, title, text) {
  const full = (title + ". " + text).replace(/\s+/g, " ").trim();
  if (!full) return null;
  const lower = full.toLowerCase();
  const combined = source + " " + lower;
  if (!combined.includes("swiggy") && !combined.includes("zomato")) return null;
  for (const kw of DROP_KEYWORDS) {
    const idx = lower.indexOf(kw);
    if (idx >= 0) { const a = clean(full.substring(idx + kw.length)); if (a) return { source, kind: "DROP", address: a, raw: full }; }
  }
  for (const kw of PICKUP_KEYWORDS) {
    const idx = lower.indexOf(kw);
    if (idx >= 0) { const a = clean(full.substring(idx + kw.length)); if (a) return { source, kind: "PICKUP", address: a, raw: full }; }
  }
  if (PINCODE.test(full) || lower.includes(" near ")) { const a = clean(full); if (a) return { source, kind: "UNKNOWN", address: a, raw: full }; }
  return null;
}

// --- Mirror of BLE chunking ---
function asciiOnly(t) { return String(t).replace(/[^\x20-\x7E]/g, ""); }
function chunkJson(js) {
  const a = asciiOnly(js), MAX = 17;
  let off = 0, seq = 0; const packets = [];
  while (off < a.length) {
    const cl = Math.min(MAX, a.length - off);
    const end = off + cl >= a.length ? "1" : "0";
    const header = seq.toString(16).padStart(2, "0").toUpperCase() + end;
    packets.push(header + a.slice(off, off + cl));
    off += cl; seq++;
    if (seq > 255) throw new Error("packet too large");
  }
  return packets;
}
function reassemble(packets) {
  return packets.map(p => p.slice(3)).join("");
}

// --- Mirror of ChunkManager (sliding window) ---
const CHUNK_SIZE = 46, OVERLAP_POINTS = 5, TRIGGER_AHEAD = 16;
let chunkStartIdx = 0, chunkEndIdx = 0;
function chunkReset() { chunkStartIdx = 0; chunkEndIdx = 0; }
function windowFor(riderIdx, total) {
  const start = Math.max(0, riderIdx - OVERLAP_POINTS);
  const end = Math.min(total, start + CHUNK_SIZE);
  return [start, end];
}
function needsNewChunk(riderIdx, total) {
  if (total <= 0) return false;
  if (chunkEndIdx >= total) return false;
  if (riderIdx >= chunkEndIdx - TRIGGER_AHEAD) return true;
  if (riderIdx + OVERLAP_POINTS < chunkStartIdx) return true;
  return false;
}
function windowChanged(start, end) { return start !== chunkStartIdx || end > chunkEndIdx; }
function applyWindow(start, end) { chunkStartIdx = start; chunkEndIdx = end; }

// ===================== TESTS =====================
let passed = 0, failed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log("PASS: " + name); }
  catch (e) { failed++; console.log("FAIL: " + name + " -> " + e.message); }
}

test("haversine 1 deg lon at equator ~111km", () => {
  assert.ok(Math.abs(haversine({ lat: 0, lon: 0 }, { lat: 0, lon: 1 }) - 111194.9) < 100);
});
test("bearing north is 0", () => {
  assert.ok(Math.abs(bearing({ lat: 13.0, lon: 80.27 }, { lat: 13.05, lon: 80.27 })) < 0.5);
});
test("bearing SW quadrant", () => {
  const b = bearing({ lat: 13.0827, lon: 80.2707 }, { lat: 13.0358, lon: 80.2565 });
  assert.ok(b >= 180 && b <= 270, "got " + b);
});
test("angleDiff wraps", () => {
  assert.strictEqual(angleDiff(350, 10), 20);
  assert.strictEqual(angleDiff(10, 350), -20);
  assert.strictEqual(angleDiff(0, 180), -180);
});
test("downsample keeps endpoints within 48 for long route", () => {
  const coords = Array.from({ length: 120 }, (_, i) => ({ lat: i * 0.001, lon: i * 0.001 }));
  const ds = downsample(coords, 48);
  assert.ok(ds.length <= 48 && ds.length >= 40, "got " + ds.length);
  assert.deepStrictEqual(ds[0], coords[0]);
  assert.deepStrictEqual(ds[ds.length - 1], coords[coords.length - 1]);
});
test("downsample unchanged for short route", () => {
  const coords = Array.from({ length: 10 }, (_, i) => ({ lat: i * 0.001, lon: 0 }));
  assert.deepStrictEqual(downsample(coords, 48), coords);
});
test("cum starts 0 and increases", () => {
  const coords = Array.from({ length: 10 }, (_, i) => ({ lat: i * 0.001, lon: 0 }));
  const cum = computeCum(coords);
  assert.strictEqual(cum[0], 0);
  for (let i = 1; i < cum.length; i++) assert.ok(cum[i] > cum[i - 1]);
});
test("straight line no turn", () => {
  const coords = Array.from({ length: 5 }, (_, i) => ({ lat: i * 0.001, lon: 0 }));
  assert.strictEqual(estimateTurnType(coords, 0), 0);
});
test("left turn detected", () => {
  assert.strictEqual(estimateTurnType([{ lat: 0, lon: 0 }, { lat: 0.01, lon: 0 }, { lat: 0.01, lon: -0.01 }], 0), 1);
});
test("right turn detected", () => {
  assert.strictEqual(estimateTurnType([{ lat: 0, lon: 0 }, { lat: 0.01, lon: 0 }, { lat: 0.01, lon: 0.01 }], 0), 2);
});
test("u-turn detected", () => {
  assert.strictEqual(estimateTurnType([{ lat: 0, lon: 0 }, { lat: 0.01, lon: 0 }, { lat: 0, lon: 0 }], 0), 5);
});
test("snap index nearest", () => {
  assert.strictEqual(snapIndex([{ lat: 0, lon: 0 }, { lat: 0.01, lon: 0 }, { lat: 0.02, lon: 0 }], { lat: 0.0101, lon: 0 }), 1);
});
test("planned speed max on straight", () => {
  const coords = Array.from({ length: 20 }, (_, i) => ({ lat: i * 0.001, lon: 0 }));
  assert.strictEqual(plannedSpeedKmh(coords, 0), 22);
});
test("sanitize road", () => {
  assert.strictEqual(sanitizeRoad("College Road"), "College Road");
  assert.strictEqual(sanitizeRoad("!!!"), "Route");
  assert.strictEqual(sanitizeRoad("A very long road name here").length, 20);
});

test("parse partner drop address", () => {
  const o = parse("in.swiggy.android", "New order assigned!", "Pickup: Olivers Kitchen, Drop: 12, Lake View Street, Anna Nagar, Chennai 600040");
  assert.strictEqual(o.kind, "DROP");
  assert.strictEqual(o.address, "12, Lake View Street, Anna Nagar, Chennai 600040");
});
test("parse zomato deliver to", () => {
  const o = parse("com.application.zomato", "Order assigned", "Deliver to 45/2, GST Road, Chromepet, Chennai");
  assert.strictEqual(o.kind, "DROP");
  assert.strictEqual(o.address, "45/2, GST Road, Chromepet, Chennai");
});
test("parse multiline big text", () => {
  const o = parse("com.swiggy.gulerix", "New order", "Pickup: Karaikudi Restaurant\nDeliver to: 8, Gandhi Road");
  assert.strictEqual(o.kind, "DROP");
  assert.strictEqual(o.address, "8, Gandhi Road");
});
test("parse pickup when no drop", () => {
  const o = parse("com.swiggy.gulerix", "New order", "Pickup: Hotel Anandha Bhavan, T Nagar");
  assert.strictEqual(o.kind, "PICKUP");
  assert.strictEqual(o.address, "Hotel Anandha Bhavan, T Nagar");
});
test("customer status without address -> null", () => {
  const o = parse("com.swiggy.gulerix", "Order update", "Your order from Sri Biryani Zone is out for delivery. Arriving in 12 mins");
  assert.strictEqual(o, null);
});
test("irrelevant notification -> null", () => {
  assert.strictEqual(parse("com.whatsapp", "Mom", "Call me when free"), null);
});
test("strips order id", () => {
  const o = parse("in.swiggy.android", "Order assigned #48291", "Deliver to 3, Cross Cut Road #48291");
  assert.strictEqual(o.address, "3, Cross Cut Road");
});
test("drop text with emoji cleaned", () => {
  const o = parse("in.swiggy.android", "New order", "Drop: 5, Beach Rd 🛵🛵");
  assert.strictEqual(o.address, "5, Beach Rd");
});

test("BLE chunk+reassemble roundtrip", () => {
  const json = '{"t":"R","pts":[[12.9716,80.2707],[13.0358,80.2565]]}';
  const packets = chunkJson(json);
  assert.strictEqual(reassemble(packets), json);
  assert.ok(packets.every(p => p.length <= 20), "max 3 header + 17 payload");
  assert.ok(packets[packets.length - 1].charAt(2) === "1", "last flag");
  assert.ok(packets.slice(0, -1).every(p => p.charAt(2) === "0"), "not-last flags");
});
test("BLE unicode road sanitized to Route before send", () => {
  const packets = chunkJson('{"t":"U","road":"' + sanitizeRoad("காந்தி சாலை") + '"}');
  assert.strictEqual(reassemble(packets), '{"t":"U","road":"Route"}');
});
test("BLE ascii strip on unicode road", () => {
  const packets = chunkJson('{"t":"U","road":"காந்தி சாலை"}');
  assert.strictEqual(reassemble(packets), '{"t":"U","road":" "}');
});
test("BLE large route packet count", () => {
  const pts = [];
  for (let i = 0; i < 48; i++) pts.push("[13.0" + (i % 10) + "68,80.27" + (i % 10) + "07]");
  const json = '{"t":"R","pts":[' + pts.join(",") + ']}';
  const packets = chunkJson(json);
  assert.strictEqual(reassemble(packets), json);
  assert.ok(packets.length < 255);
});

test("windowFor clamps at start and end", () => {
  assert.deepStrictEqual(windowFor(0, 100), [0, 46]);
  assert.deepStrictEqual(windowFor(3, 100), [0, 46]);
  assert.deepStrictEqual(windowFor(10, 100), [5, 51]);
  assert.deepStrictEqual(windowFor(98, 100), [93, 100]);
  assert.deepStrictEqual(windowFor(0, 20), [0, 20]);
});
test("needsNewChunk forward trigger", () => {
  chunkReset(); applyWindow(0, 46);
  assert.strictEqual(needsNewChunk(0, 413), false);
  assert.strictEqual(needsNewChunk(29, 413), false);
  assert.strictEqual(needsNewChunk(30, 413), true);
});
test("needsNewChunk behind trigger", () => {
  chunkReset(); applyWindow(30, 76);
  assert.strictEqual(needsNewChunk(2, 413), true);
  assert.strictEqual(needsNewChunk(26, 413), false);
});
test("final chunk guard stops re-sends", () => {
  chunkReset(); applyWindow(69, 90);
  assert.strictEqual(needsNewChunk(89, 90), false);
  assert.strictEqual(needsNewChunk(80, 90), false);
});
test("sliding window covers full route", () => {
  const total = 413;
  chunkReset();
  const [s0, e0] = windowFor(0, total);
  applyWindow(s0, e0);
  let chunks = 0, maxChunk = 0, rider = 0;
  while (rider < total) {
    if (needsNewChunk(rider, total)) {
      const [start, end] = windowFor(rider, total);
      if (windowChanged(start, end)) { applyWindow(start, end); chunks++; maxChunk = Math.max(maxChunk, end - start); }
    }
    assert.ok(rider >= chunkStartIdx && rider < chunkEndIdx, `rider ${rider} outside [${chunkStartIdx}, ${chunkEndIdx})`);
    rider++;
  }
  assert.ok(chunks > 5, "chunks " + chunks);
  assert.ok(maxChunk <= CHUNK_SIZE, "maxChunk " + maxChunk);
  assert.strictEqual(chunkEndIdx, total);
});

console.log("\n" + passed + " passed, " + failed + " failed");
process.exit(failed > 0 ? 1 : 0);
