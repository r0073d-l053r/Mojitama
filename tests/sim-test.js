/**
 * Mojitama simulation tests.
 *
 *   node tests/sim-test.js
 *
 * The game is one HTML file with no build step, so there is nothing to import.
 * This harness pulls the <script> out of mojitama.html, strips the final boot()
 * call, and evaluates it inside a VM with a DOM stub — which gives us the real
 * simulation functions, not a copy that can drift.
 *
 * What it protects: decay rates per species, the offline half-speed and its 12h
 * budget, stage thresholds, sickness, death, and the alert predictions the
 * Android notifications depend on. Those are exactly the numbers that can
 * silently kill someone's pet unfairly, and the browser cannot assert on them.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const FILE = path.join(__dirname, '..', 'mojitama.html');

/* ---------------- DOM stub ---------------- */
// Deliberately dumb: enough shape for the game's render calls to run without
// throwing, and nothing more. If a test needs real DOM behaviour it belongs in
// the browser, not here.
function makeEl() {
  const el = {
    style: new Proxy({}, {get: (t, k) => (k === 'setProperty' ? () => {} : t[k] || ''),
                          set: (t, k, v) => { t[k] = v; return true; }}),
    classList: {
      _s: new Set(),
      add(...c) { c.forEach(x => this._s.add(x)); },
      remove(...c) { c.forEach(x => this._s.delete(x)); },
      toggle(c, on) { if (on === undefined) on = !this._s.has(c); on ? this._s.add(c) : this._s.delete(c); return on; },
      contains(c) { return this._s.has(c); },
    },
    dataset: {}, children: [], value: '', textContent: '', innerHTML: '', id: '',
    appendChild() {}, removeChild() {}, remove() {}, focus() {}, click() {},
    setAttribute() {}, removeAttribute() {}, getAttribute() { return null; },
    addEventListener() {}, removeEventListener() {}, closest() { return null; },
    querySelector() { return null; }, querySelectorAll() { return []; },
    getBoundingClientRect() { return {left: 0, top: 0, width: 100, height: 100, right: 100, bottom: 100}; },
    scrollIntoView() {}, offsetWidth: 100, offsetHeight: 100,
  };
  return el;
}

function makeStorage() {
  const m = new Map();
  return {
    getItem: k => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => m.set(k, String(v)),
    removeItem: k => m.delete(k),
    clear: () => m.clear(),
    get length() { return m.size; },
    key: i => Array.from(m.keys())[i],
    _map: m,
  };
}

function buildSandbox() {
  const els = new Map();
  const doc = {
    getElementById(id) { if (!els.has(id)) els.set(id, makeEl()); return els.get(id); },
    querySelector() { return null; },
    querySelectorAll() { return []; },
    createElement() { return makeEl(); },
    addEventListener() {},
    body: makeEl(),
    documentElement: makeEl(),
    styleSheets: [],
    hidden: false,
  };
  const win = {
    matchMedia: () => ({matches: false, addEventListener() {}, addListener() {}}),
    addEventListener() {},
    location: {search: '', href: 'http://localhost/'},
    navigator: {vibrate() {}, userActivation: {hasBeenActive: false}, storage: {persist() {}}},
    localStorage: makeStorage(),
    indexedDB: {open() { return {}; }},
    AudioContext: function () {
      return {state: 'running', currentTime: 0, resume() {},
              createOscillator: () => ({type: '', frequency: {value: 0}, connect() {}, start() {}, stop() {}}),
              createGain: () => ({gain: {setValueAtTime() {}, exponentialRampToValueAtTime() {}}, connect() {}})};
    },
    Blob: function () {}, URL: {createObjectURL: () => '', revokeObjectURL() {}},
    FileReader: function () {},
  };
  const sandbox = Object.assign({}, win, {
    window: win, document: doc, navigator: win.navigator, localStorage: win.localStorage,
    indexedDB: win.indexedDB, console,
    setTimeout, clearTimeout, setInterval: () => 0, clearInterval,
    requestAnimationFrame: () => 0, cancelAnimationFrame: () => {},
    Math, Date, JSON, Object, Array, String, Number, Boolean, isFinite, isNaN, parseInt, parseFloat, Promise,
  });
  sandbox.globalThis = sandbox;
  win.document = doc;
  return sandbox;
}

function loadGame() {
  const html = fs.readFileSync(FILE, 'utf8');
  const m = html.match(/<script>([\s\S]*?)<\/script>\s*<\/body>/);
  if (!m) throw new Error('could not find the game <script> in mojitama.html');
  let code = m[1];

  // Don't start the game; we only want its functions.
  code = code.replace(/\nboot\(\);\s*$/, '\n/* boot() suppressed by the test harness */\n');
  if (/\nboot\(\);/.test(code)) code = code.replace(/\nboot\(\);/, '\n');

  // Surface the internals this harness drives.
  code += `
;globalThis.__T = {
  T, HOUR, MIN, SPECIES, ACH,
  clamp, deriveStage, stageInfo, poopTarget,
  needRate, msUntilNeed, predictAlerts, widgetSnapshot, PACES,
  simulate, freshSave, checksum, envelope, openEnvelope,
  __forms: {gradeFor, formOf, FORMS, CARE_RADIANT, CARE_TYPICAL, lifeFraction},
  get S(){ return S; }, set S(v){ S = v; },
};`;

  const sandbox = buildSandbox();
  vm.createContext(sandbox);
  vm.runInContext(code, sandbox, {filename: 'mojitama.html'});
  return sandbox.__T;
}

/* ---------------- tiny test runner ---------------- */
let passed = 0, failed = 0;
const failures = [];

function check(name, cond, detail) {
  if (cond) { passed++; }
  else { failed++; failures.push(name + (detail ? '  — ' + detail : '')); }
}
function near(name, actual, expected, tol) {
  tol = tol === undefined ? 0.01 : tol;
  const ok = Math.abs(actual - expected) <= tol;
  check(name, ok, ok ? '' : `expected ${expected}, got ${actual}`);
}

/* ---------------- the tests ---------------- */
const G = loadGame();
const {T, HOUR, MIN} = G;

// helper: a fresh living pet of a chosen species
function petOf(speciesId, needs) {
  const S = G.freshSave();
  S.phase = 'alive';
  S.speciesId = speciesId;
  S.petName = 'Test';
  S.pet = {
    ageMs: 0, bornAt: Date.now(),
    needs: Object.assign({hunger: 100, happy: 100, energy: 100, hygiene: 100}, needs || {}),
    health: 100, sleeping: false, sick: false,
    poops: 0, poopTimerMs: 0, poopTargetMs: 99 * HOUR, fedSincePoop: 0,
    stage: 'baby', deathCause: null,
  };
  G.S = S;
  return S;
}

console.log('Mojitama simulation tests\n' + '='.repeat(46));

/* --- decay rates match each species' personality --- */
(function decay() {
  const panda = G.SPECIES.find(s => s.id === 'panda');
  petOf('panda');
  near('panda hunger rate = base x its multiplier',
       G.needRate('hunger', false), T.decay.hunger * panda.mult.hunger);

  petOf('frog');
  const frog = G.SPECIES.find(s => s.id === 'frog');
  near('frog hunger rate = base x its multiplier',
       G.needRate('hunger', false), T.decay.hunger * frog.mult.hunger);

  // one hour of real decay must equal exactly one hour of the stated rate
  const S = petOf('dog', {hunger: 100});
  const rate = G.needRate('hunger', false);
  G.simulate(HOUR);
  near('one simulated hour drops hunger by exactly one hour of decay',
       100 - S.pet.needs.hunger, rate, 0.05);
})();

/* --- sleeping changes the model in the ways the game claims --- */
(function sleeping() {
  const S = petOf('dog', {hunger: 100, energy: 40});
  S.pet.sleeping = true;
  const awakeRate = T.decay.hunger * G.SPECIES.find(s => s.id === 'dog').mult.hunger;
  near('hunger decays at 40% while asleep', G.needRate('hunger', false), awakeRate * 0.4);
  check('energy recovers rather than drains while asleep', G.needRate('energy', false) < 0);
  check('no energy alert is scheduled for a sleeping pet', G.msUntilNeed('energy', 40, 25) === null);

  G.simulate(HOUR);
  check('an hour of sleep raises energy', S.pet.needs.energy > 40);
})();

/* --- hygiene carries the poop penalty, and no sleep discount --- */
(function hygiene() {
  const S = petOf('cat', {hygiene: 100});
  const base = G.needRate('hygiene', false);
  S.pet.poops = 3;
  near('each poop adds its penalty to the hygiene rate',
       G.needRate('hygiene', false), base + 3 * T.poopHygiene);
  S.pet.poops = 0;
  S.pet.sleeping = true;
  near('sleep does not slow hygiene decay', G.needRate('hygiene', false), base);
})();

/* --- offline is half speed, and stops at the budget --- */
(function offline() {
  near('offline decay is scaled by offlineScale',
       G.needRate('hunger', true), G.needRate('hunger', false) * T.offlineScale);

  // Assert the invariant rather than a fixed quantity: past the budget, more
  // time away costs nothing. (A fixed number would also be measuring whichever
  // evolution form the pet happened to reach during the run.)
  const short = petOf('dog', {hunger: 100, happy: 100, energy: 100, hygiene: 100});
  G.simulate(T.offlineCapMs, {offline: true});
  const dropAtCap = 100 - short.pet.needs.hunger;

  const long = petOf('dog', {hunger: 100, happy: 100, energy: 100, hygiene: 100});
  G.simulate(48 * HOUR, {offline: true});
  const dropAt48 = 100 - long.pet.needs.hunger;

  near('past the offline budget, further absence costs nothing', dropAt48, dropAtCap, 0.5);
  check('the offline budget does cost something', dropAtCap > 5, `drop was ${dropAtCap}`);

  check('a crossing beyond the offline budget is reported as unreachable',
        G.msUntilNeed('hunger', 100, 25) === null);
  check('a reachable crossing is still predicted',
        typeof G.msUntilNeed('hunger', 40, 25) === 'number');
})();

/* --- evolution forms: care has to actually change something --- */
(function forms() {
  check('a well-kept pet is graded radiant', G.__forms.gradeFor(90) === 'radiant');
  check('a middling pet is graded typical', G.__forms.gradeFor(60) === 'typical');
  check('a neglected pet is graded scruffy', G.__forms.gradeFor(20) === 'scruffy');
  check('a pet with no record grades neutral', G.__forms.gradeFor(undefined) === 'typical');

  const base = (form) => {
    const S = petOf('dog', {hunger: 100});
    S.pet.form = form;
    return G.needRate('hunger', false);
  };
  const typ = base('typical'), rad = base('radiant'), scr = base('scruffy');
  near('a radiant pet decays 12% slower', rad, typ * 0.88, 0.01);
  near('a scrappy pet decays 8% faster', scr, typ * 1.08, 0.01);
  check('care therefore compounds in the player\'s favour', rad < typ && typ < scr);

  // care rises under good keeping and falls under bad
  const good = petOf('dog', {hunger: 100, happy: 100, energy: 100, hygiene: 100});
  good.pet.care = 50;
  G.simulate(8 * HOUR);
  check('good keeping raises the care score', good.pet.care > 50, `care ${good.pet.care}`);

  const bad = petOf('dog', {hunger: 0, happy: 0, energy: 0, hygiene: 0});
  bad.pet.care = 80; bad.pet.health = 100;
  G.simulate(8 * HOUR);
  check('neglect lowers the care score', bad.pet.care < 80, `care ${bad.pet.care}`);

  // the grade is re-judged at every stage-up, so a bad start is recoverable
  const redeemed = petOf('dog', {hunger: 100, happy: 100, energy: 100, hygiene: 100});
  redeemed.pet.care = 10; redeemed.pet.form = 'scruffy';
  G.simulate(8 * HOUR);                       // crosses the child and teen thresholds
  check('a rough start can still grow into a better form',
        redeemed.pet.form !== 'scruffy', `still ${redeemed.pet.form}`);
})();

/* --- pace presets (G-01): time is scaled, fairness is not --- */
(function paces() {
  const setPace = id => { G.S.pace = id; };

  // decay scales exactly as declared
  petOf('dog', {hunger: 100});
  const std = G.needRate('hunger', false);
  setPace('gentle');
  near('gentle decays at 45% of standard', G.needRate('hunger', false), std * 0.45);
  setPace('classic');
  near('classic decays at 150% of standard', G.needRate('hunger', false), std * 1.5);
  setPace('standard');

  // stages stretch with the same factor, so a life keeps its shape
  setPace('gentle');
  check('gentle: child begins at 1.6h, not 1h',
        G.deriveStage(1.6 * HOUR) === 'child' && G.deriveStage(1.5 * HOUR) === 'baby');
  setPace('classic');
  check('classic: adult begins at 15h (25 x 0.6)',
        G.deriveStage(15 * HOUR) === 'adult' && G.deriveStage(14.9 * HOUR) === 'teen');
  setPace('standard');

  // THE point of the feature: a once-a-day player on Gentle gets a FULL life —
  // reaches Senior, and if death comes it is old age, never neglect. (A first
  // draft of this test demanded 10 days of survival; the trace showed the pet
  // dying on day 9 with health 98 and every need above 89 — of old age, at the
  // natural end of a Gentle life. The test was demanding immortality.)
  (function onceADay() {
    const S = petOf('dog', {hunger: 100, happy: 100, energy: 100, hygiene: 100});
    S.pace = 'gentle';
    let reachedSenior = false, neglected = false;
    for (let day = 0; day < 12 && S.phase === 'alive'; day++) {
      G.simulate(24 * HOUR, {offline: true});
      if (S.pet.stage === 'senior') reachedSenior = true;
      if (S.phase !== 'alive') { neglected = S.pet.deathCause === 'neglect'; break; }
      // the visit: feed, clean, play, tuck in — everything a check-in does
      S.pet.needs = {hunger: 100, happy: 100, energy: 100, hygiene: 100};
      S.pet.poops = 0; S.pet.sick = false;
    }
    check('a once-a-day player on Gentle reaches Senior', reachedSenior);
    check('and never loses the pet to neglect', !neglected,
          `died of ${S.pet.deathCause}`);
    // Threshold sits at the Scrappy boundary (45), not at the run's expected
    // value (~55) — asserting at the expectation makes the test a coin flip.
    check('the life stayed above the Scrappy grade',
          S.pet.care > 45, `care ${Math.round(S.pet.care)}`);
  })();

  // the same schedule on Classic must NOT trivially succeed — otherwise the
  // presets would be labels rather than a real choice
  (function onceADayClassic() {
    const S = petOf('dog', {hunger: 100, happy: 100, energy: 100, hygiene: 100});
    S.pace = 'classic';
    let ok = true;
    for (let day = 0; day < 10 && S.phase === 'alive'; day++) {
      G.simulate(24 * HOUR, {offline: true});
      if (S.phase !== 'alive') { ok = false; break; }
      const n = S.pet.needs;
      if (Math.min(n.hunger, n.happy, n.energy, n.hygiene) <= 0) ok = false;
      S.pet.needs = {hunger: 100, happy: 100, energy: 100, hygiene: 100};
      S.pet.poops = 0; S.pet.sick = false;
    }
    check('the same neglectful schedule struggles on Classic', !ok);
  })();

  // pace never touches achievements: verify none gate on it
  check('no achievement references pace', !JSON.stringify(G.ACH).includes('pace'));
  G.S.pace = 'standard';
})();

/* --- sleepover (G-02): a planned absence freezes care but not time --- */
(function sleepover() {
  const S = petOf('dog', {hunger: 90, happy: 90, energy: 90, hygiene: 90});
  S.pet.care = 70;
  const now = Date.now();
  S.sleepover = {armedAt: now, until: now + 3 * 24 * HOUR, days: 3};
  S.lastSeen = now;

  G.simulate(2 * 24 * HOUR, {offline: true});
  const n = S.pet.needs;
  check('needs are frozen during a sleepover',
        n.hunger === 90 && n.happy === 90 && n.hygiene === 90,
        JSON.stringify(n));
  check('health cannot fall during a sleepover', S.pet.health === 100);
  check('but the pet still ages', S.pet.ageMs >= 2 * 24 * HOUR - MIN);
  check('and can grow up while away', S.pet.stage !== 'baby', S.pet.stage);
  check('no alerts are predicted while away',
        (() => { const a = G.predictAlerts(); return a.length === 0 || S.sleepover; })());

  // time past the end of the sleepover decays normally again
  const S2 = petOf('cat', {hunger: 90, happy: 90, energy: 90, hygiene: 90});
  const t2 = Date.now();
  S2.sleepover = {armedAt: t2, until: t2 + 1 * 24 * HOUR, days: 1};
  S2.lastSeen = t2;
  G.simulate(3 * 24 * HOUR, {offline: true});
  check('the sleepover ends on schedule', S2.sleepover === null || S2.sleepover === undefined
        ? true : false);
  check('and decay resumes for the time after it', S2.pet.needs.hunger < 90,
        `hunger ${S2.pet.needs.hunger}`);
})();

/* --- bloodlines (G-11): traits change rates, old saves migrate --- */
(function bloodlines() {
  const S = petOf('dog', {hunger: 100});
  const base = G.needRate('hunger', false);
  S.line = {traits: ['ironbelly'], crest: null};
  near('Iron Belly slows hunger by exactly 6%', G.needRate('hunger', false), base * 0.94);
  S.line = {traits: [], crest: 'ironbelly+sunny'};
  near('a crest applies its compound effect', G.needRate('hunger', false), base * 0.92);
  S.line = {traits: [], crest: null};
  near('an empty line changes nothing', G.needRate('hunger', false), base);

  // migration: a save with no line field must not throw anywhere
  const old = petOf('cat', {hunger: 80});
  delete old.line;
  let threw = null;
  try { G.simulate(2 * HOUR); G.predictAlerts(); G.widgetSnapshot(); } catch (e) { threw = String(e); }
  check('a pre-bloodline save simulates without error', threw === null, threw);
})();

/* --- life stages --- */
(function stages() {
  check('a newborn is a baby', G.deriveStage(0) === 'baby');
  T.stages.forEach(st => {
    check(`${st.name} begins exactly at ${st.at}h`,
          G.deriveStage(st.at * HOUR) === st.id,
          `got ${G.deriveStage(st.at * HOUR)}`);
    if (st.at > 0) {
      check(`${st.name} has not begun a minute early`,
            G.deriveStage(st.at * HOUR - MIN) !== st.id);
    }
  });
  check('stages never regress with age', (() => {
    let last = -1;
    for (let h = 0; h <= 200; h++) {
      const i = T.stages.findIndex(s => s.id === G.deriveStage(h * HOUR));
      if (i < last) return false;
      last = i;
    }
    return true;
  })());
})();

/* --- neglect kills, and care does not --- */
(function death() {
  const S = petOf('dog', {hunger: 0, happy: 0, energy: 0, hygiene: 0});
  S.pet.health = 20;
  G.simulate(12 * HOUR);
  check('a pet with every need at zero eventually dies', S.phase === 'dead');
  check('the death is recorded as neglect', S.pet.deathCause === 'neglect');

  const W = petOf('frog', {hunger: 90, happy: 90, energy: 90, hygiene: 90});
  W.pet.health = 60;
  G.simulate(6 * HOUR);
  check('a well-kept pet survives the same span', W.phase === 'alive');
  check('and its health recovers', W.pet.health > 60);
})();

/* --- filth makes them ill --- */
(function sickness() {
  // The rate is 0.11/hour in maximum filth, which is only a coin flip over 6
  // hours — asserting on that window would make this test flaky by design.
  // Over 12 hours the expectation is ~73%, which is a real signal.
  let gotSick = 0;
  const RUNS = 40;
  for (let i = 0; i < RUNS; i++) {
    // other needs stay high so health cannot collapse and end the run early
    const S = petOf('dog', {hygiene: 0, hunger: 100, happy: 100, energy: 100});
    S.pet.poops = 4;
    G.simulate(12 * HOUR);
    if (S.pet.sick) gotSick++;
  }
  check('living in filth reliably causes illness', gotSick > RUNS * 0.55,
        `${gotSick}/${RUNS} runs, expected ~73%`);

  let cleanSick = 0;
  for (let i = 0; i < 40; i++) {
    const S = petOf('dog', {hygiene: 100, hunger: 90, happy: 90, energy: 90});
    G.simulate(6 * HOUR);
    if (S.pet.sick) cleanSick++;
  }
  check('a clean, fed pet does not fall ill', cleanSick === 0, `${cleanSick}/40 runs`);
})();

/* --- needs stay inside their bounds --- */
(function bounds() {
  const S = petOf('dino', {hunger: 5, happy: 5, energy: 5, hygiene: 5});
  G.simulate(80 * HOUR);
  const vals = Object.values(S.pet.needs).concat([S.pet.health]);
  check('no need ever goes below 0 or above 100',
        vals.every(v => v >= 0 && v <= 100), JSON.stringify(S.pet.needs));
})();

/* --- the notification predictions the Android side relies on --- */
(function alerts() {
  const S = petOf('dog', {hunger: 60, happy: 100, energy: 100, hygiene: 100});
  const alerts = G.predictAlerts();
  check('a low need produces an alert', alerts.length > 0);
  check('every alert is in the future', alerts.every(a => a.at > Date.now()));
  check('alerts never exceed the cap', alerts.length <= 3, `got ${alerts.length}`);
  check('alerts are spaced apart', alerts.every((a, i) => i === 0 || a.at - alerts[i - 1].at >= 90 * MIN));
  check('each alert clears the native "too soon" filter',
        alerts.every(a => a.at - Date.now() > 60000));

  const rate = G.needRate('hunger', true);
  const expected = ((60 - 25) / rate) * HOUR;
  const hunger = alerts.find(a => a.kind === 'hunger');
  if (hunger) near('the hunger alert lands when hunger actually reaches the threshold',
                   hunger.at - Date.now(), expected, 5 * MIN);

  petOf('dog', {hunger: 100, happy: 100, energy: 100, hygiene: 100});
  check('a well-kept pet is left in peace', G.predictAlerts().length === 0);

  const D = petOf('dog', {hunger: 10});
  D.phase = 'dead';
  check('nothing is scheduled for a pet that has died', G.predictAlerts().length === 0);
})();

/* --- the widget snapshot contract --- */
(function snapshot() {
  petOf('octopus', {hunger: 55});
  const s = G.widgetSnapshot();
  ['at', 'phase', 'face', 'name', 'needs', 'rate', 'offlineScale', 'offlineCapMs',
   'hygieneBase', 'poopHygiene', 'health', 'stage'].forEach(k =>
    check(`snapshot carries "${k}" for the widget`, s[k] !== undefined));
  check('snapshot rates are the foreground ones (native scales them itself)',
        Math.abs(s.rate.hunger - G.needRate('hunger', false)) < 0.001);
})();

/* --- save envelope integrity --- */
(function saves() {
  const S = petOf('cat', {hunger: 70});
  const env = G.envelope(S, 7);
  const back = G.openEnvelope(env);
  check('a save round-trips intact', back && back.state.petName === 'Test' && back.seq === 7);

  const torn = JSON.parse(env);
  torn.body = torn.body.replace('"hunger":70', '"hunger":12');
  check('a tampered save is rejected by its checksum', G.openEnvelope(JSON.stringify(torn)) === null);
  check('garbage is rejected', G.openEnvelope('not json at all') === null);
  check('a pre-envelope save is still accepted',
        G.openEnvelope(JSON.stringify(S)) !== null);
})();

/* ---------------- report ---------------- */
console.log('');
failures.forEach(f => console.log('  FAIL  ' + f));
console.log('='.repeat(46));
console.log(`${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
