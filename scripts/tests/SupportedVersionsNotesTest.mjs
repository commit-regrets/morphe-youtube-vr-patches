// scripts/tests/SupportedVersionsNotesTest.mjs
// Headless check of the Constants.kt reader that both the upstream checker and the release-notes
// plugin share (scripts/supported-versions.mjs), and of the "Supported app versions" section that
// the plugin appends to every release's notes. The section is rendered during a release from
// whatever Constants.kt holds at that moment, so the shapes it can meet -- one target, two,
// an experimental target, the commented-out policy example -- are asserted here rather than
// discovered live in the release workflow.
//
// Run: node scripts/tests/SupportedVersionsNotesTest.mjs

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  CONSTANTS_FILE,
  parseSupportedAppVersion,
  parseSupportedVersions,
  parseTargets,
  renderSupportedVersionsSection,
} from '../supported-versions.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

let failures = 0;

function check(what, ok) {
  if (!ok) {
    failures++;
  }
  console.log((ok ? '  ok   ' : '  FAIL ') + what);
}

function equal(what, got, want) {
  check(`${what}: want ${JSON.stringify(want)} (got ${JSON.stringify(got)})`, got === want);
}

// ---- parsing the declarations ------------------------------------------------

// The shipped shape: two normal targets, newest first, plus the commented-out example in the
// policy block that the regex must NOT see (its `\s*` cannot cross the example's `//` marker --
// the property SupportedVersionsPolicyTest mirrors). SUPPORTED_APP_VERSION is also excluded from
// parseTargets: it names a target, and counting it twice would double-list it.
const shipped = `
const val SUPPORTED_APP_VERSION = "1.90.05"
val targets = listOf(
    //     AppTarget(
    //         version = "1.88.10",
    //         isExperimental = true,
    AppTarget(
        version = "1.90.05",
        description = "Verified.",
    ),
    AppTarget(
        version = "1.89.09",
        description = "Verified.",
    ),
)`;
equal('shipped: two targets', parseTargets(shipped).length, 2);
equal(
  'shipped: versions in file order',
  parseTargets(shipped).map((t) => t.version).join(','),
  '1.90.05,1.89.09'
);
check(
  'shipped: none experimental',
  parseTargets(shipped).every((t) => !t.experimental)
);
equal('shipped: flat list', parseSupportedVersions(shipped).join(','), '1.90.05,1.89.09');
equal('shipped: constant', parseSupportedAppVersion(shipped), '1.90.05');

// An experimental target is parsed as one: it is attempted, not verified, and the rendered
// section has to keep that distinction.
const withExperimental = `
AppTarget(
    version = "1.90.05",
    description = "Verified.",
),
AppTarget(
    version = "1.91.0",
    isExperimental = true,
    description = "Untested.",
),`;
const parsed = parseTargets(withExperimental);
equal('experimental: target count', parsed.length, 2);
equal('experimental: normal flag', parsed[0].experimental, false);
equal('experimental: experimental flag', parsed[1].experimental, true);

// A constant naming a version no target lists still pins the spoof default, so the flat list
// (the upstream checker's comparison set) must include it -- exactly the old checker's semantics.
equal(
  'constant without a target joins the flat list',
  parseSupportedVersions('AppTarget(version = "1.90.05")\nconst val SUPPORTED_APP_VERSION = "1.91.0"').join(','),
  '1.90.05,1.91.0'
);
equal(
  'constant naming a listed target is not repeated',
  parseSupportedVersions('AppTarget(version = "1.91.0")\nconst val SUPPORTED_APP_VERSION = "1.91.0"').join(','),
  '1.91.0'
);

// ---- rendering the section ---------------------------------------------------

const twoNormal = renderSupportedVersionsSection(parseTargets(shipped));
check('render: two normal targets', twoNormal.includes('**YouTube VR 1.90.05** and **1.89.09**'));
check('render: no experimental line', !twoNormal.toLowerCase().includes('experimental'));
check('render: says other versions are skipped', twoNormal.includes('skipped by the patcher'));
check('render: section heading', twoNormal.startsWith('### 📱 Supported app versions'));
check('render: docs pointer', twoNormal.includes('docs/VERSIONING.md'));

const singular = renderSupportedVersionsSection([{ version: '1.90.05', experimental: false }]);
check('render: singular form', singular.includes('**YouTube VR 1.90.05** (Meta Quest)'));
check('render: singular has no "and"', !singular.includes(' and '));

const withExp = renderSupportedVersionsSection(parseTargets(withExperimental));
check('render: experimental carries its own line', withExp.includes('Experimental -- attempted, not verified: **1.91.0**.'));
check('render: normal line lists only the verified one', withExp.includes('**YouTube VR 1.90.05** (Meta Quest)'));

// An empty Constants.kt must fail loudly rather than publish a section that supports nothing.
let threw = false;
try {
  renderSupportedVersionsSection([]);
} catch {
  threw = true;
}
check('render: no targets at all throws', threw);

// ---- the real Constants.kt ----------------------------------------------------

// The JS invariant SupportedVersionsPolicyTest enforces in Kotlin: SUPPORTED_APP_VERSION names
// the newest normal target. Asserted here too because this module is the other reader of the
// file -- if the two ever disagree, one of them has been blinded.
const real = fs.readFileSync(CONSTANTS_FILE, 'utf8');
const realTargets = parseTargets(real);
const realNormal = realTargets.filter((t) => !t.experimental);
const constant = parseSupportedAppVersion(real);
check('real: at least one target parsed', realTargets.length > 0);
equal('real: newest normal target is SUPPORTED_APP_VERSION', realNormal[0]?.version, constant);
const section = renderSupportedVersionsSection(realTargets);
check(
  'real: every normal target named in the section',
  realNormal.every((t) => section.includes(t.version))
);

console.log(
  failures === 0
    ? 'SupportedVersionsNotesTest: OK (every expectation held)'
    : `SupportedVersionsNotesTest: ${failures} FAILURE(S)`
);
process.exit(failures === 0 ? 0 : 1);
