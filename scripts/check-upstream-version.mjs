// scripts/check-upstream-version.mjs
// Checks Meta Quest Store for new releases of YouTube VR (com.google.android.apps.youtube.vr.oculus).
// Compares against pinned targets in patches/src/main/kotlin/app/ytvr/patches/shared/Constants.kt.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const REPO_ROOT = path.resolve(__dirname, '..');

const APP_ID = '2002317119880945';
const STORE_ACCESS_TOKEN = 'OC|752908224809889|';
const GRAPHQL_ENDPOINT = 'https://graph.oculus.com/graphql';
const DOC_ID_RELEASE_CHANNELS = '3828663700542720';

const CONSTANTS_FILE = path.join(
  REPO_ROOT,
  'patches',
  'src',
  'main',
  'kotlin',
  'app',
  'ytvr',
  'patches',
  'shared',
  'Constants.kt'
);

const ISSUE_BODY_FILE = path.join(REPO_ROOT, 'scripts', 'upstream-update-issue.md');

async function fetchLatestStoreBinary() {
  const body = new URLSearchParams({
    access_token: STORE_ACCESS_TOKEN,
    doc_id: DOC_ID_RELEASE_CHANNELS,
    variables: JSON.stringify({ applicationID: APP_ID }),
  });

  const res = await fetch(GRAPHQL_ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: body.toString(),
  });

  if (!res.ok) {
    throw new Error(`Meta Quest Store API returned HTTP ${res.status}: ${res.statusText}`);
  }

  const data = await res.json();
  const channels = data?.data?.node?.release_channels?.nodes;
  if (!channels || channels.length === 0) {
    throw new Error('No release channels found in Meta Quest Store response');
  }

  // Look for LIVE channel, fallback to first channel
  const liveChannel = channels.find((c) => c.channel_name === 'LIVE') || channels[0];
  const binary = liveChannel.latest_supported_binary;
  if (!binary || !binary.version) {
    throw new Error(`No latest_supported_binary found in channel "${liveChannel.channel_name}"`);
  }

  return {
    channel: liveChannel.channel_name,
    version: binary.version,
    versionCode: binary.version_code,
    createdDate: binary.created_date ? new Date(binary.created_date * 1000).toISOString() : null,
    binaryId: binary.id,
  };
}

function parseSupportedVersions() {
  if (!fs.existsSync(CONSTANTS_FILE)) {
    throw new Error(`Constants.kt not found at: ${CONSTANTS_FILE}`);
  }

  const content = fs.readFileSync(CONSTANTS_FILE, 'utf8');
  // Matches AppTarget( ... version = "X.Y.Z" ... ) -- literal targets -- plus the
  // SUPPORTED_APP_VERSION constant that the verified target references. The inline `//`
  // before `version` in Constants.kt's commented-out example cannot be matched by `\s*`,
  // so commented targets stay excluded.
  const versionRegex = /AppTarget\s*\(\s*version\s*=\s*"([^"]+)"/g;
  const constantRegex = /const\s+val\s+SUPPORTED_APP_VERSION\s*=\s*"([^"]+)"/;
  const versions = [];
  let match;
  while ((match = versionRegex.exec(content)) !== null) {
    versions.push(match[1]);
  }
  const constantMatch = content.match(constantRegex);
  if (constantMatch && !versions.includes(constantMatch[1])) {
    versions.push(constantMatch[1]);
  }

  return versions;
}

function setGithubOutput(key, value) {
  const outputFile = process.env.GITHUB_OUTPUT;
  if (outputFile) {
    fs.appendFileSync(outputFile, `${key}=${value}\n`, 'utf8');
  }
}

function appendGithubSummary(text) {
  const summaryFile = process.env.GITHUB_STEP_SUMMARY;
  if (summaryFile) {
    fs.appendFileSync(summaryFile, `${text}\n`, 'utf8');
  }
}

function generateIssueBody(storeInfo, supportedVersions) {
  return `### 🚀 Upstream Update Detected

A new version of **YouTube VR** was detected on the Meta Quest Store:
- **Version**: \`${storeInfo.version}\`
- **Version Code**: \`${storeInfo.versionCode}\`
- **Release Channel**: \`${storeInfo.channel}\`
- **Release Date**: ${storeInfo.createdDate || 'N/A'}
- **Package Name**: \`com.google.android.apps.youtube.vr.oculus\`
- **Store Item ID**: \`2002317119880945\`
- **Currently Supported in Repo**: ${supportedVersions.map((v) => `\`${v}\``).join(', ')}

---

### Maintainer Checklist (from [\`docs/VERSIONING.md\`](docs/VERSIONING.md))

- [ ] **1. Obtain the APK**:
  - Back up the APK from headset using Quest Games Optimizer (QGO) or \`adb pull\` (see [\`docs/GETTING-THE-APK.md\`](docs/GETTING-THE-APK.md)).
- [ ] **2. Register in \`Constants.kt\`**:
  - Add \`AppTarget(version = "${storeInfo.version}", isExperimental = true, description = "Untested: ...")\` to [\`Constants.kt\`](patches/src/main/kotlin/app/ytvr/patches/shared/Constants.kt).
- [ ] **3. Test with diagnostics**:
  - Apply patch bundle with \`-OenableDiagnostics=true --force --continue-on-error\`.
  - Check which fingerprints fail in the patcher log.
- [ ] **4. Re-anchor fingerprints**:
  - Reference [\`docs/FINGERPRINTS.md\`](docs/FINGERPRINTS.md) and update matchers in \`patches/\`.
- [ ] **5. Verification**:
  - [ ] Bundle applies cleanly with zero failures.
  - [ ] Decompile patched dex and verify injected bytecode lands in correct target methods.
  - [ ] Headset test: Verify ad blocking, SponsorBlock segments, and playback work.
- [ ] **6. Release**:
  - Change target from \`isExperimental = true\` to verified target.
  - Commit and push to \`develop\` branch.
`;
}

async function main() {
  console.log('Querying Meta Quest Store for YouTube VR (App ID: ' + APP_ID + ')...');
  const storeInfo = await fetchLatestStoreBinary();
  console.log(`Store version: ${storeInfo.version} (version code: ${storeInfo.versionCode}, release channel: ${storeInfo.channel})`);

  const supportedVersions = parseSupportedVersions();
  console.log(`Locally supported versions in Constants.kt: ${supportedVersions.join(', ')}`);

  const isSupported = supportedVersions.includes(storeInfo.version);
  const hasUpdate = !isSupported;

  setGithubOutput('has_update', hasUpdate.toString());
  setGithubOutput('latest_version', storeInfo.version);
  setGithubOutput('latest_version_code', storeInfo.versionCode.toString());
  setGithubOutput('release_channel', storeInfo.channel);
  setGithubOutput('created_date', storeInfo.createdDate || '');
  setGithubOutput('supported_versions', supportedVersions.join(', '));
  setGithubOutput('issue_body_path', ISSUE_BODY_FILE);

  const summary = [
    '### Meta Quest Store Version Check',
    `- **Store Version**: \`${storeInfo.version}\` (Code: \`${storeInfo.versionCode}\`)`,
    `- **Channel**: \`${storeInfo.channel}\``,
    `- **Created Date**: ${storeInfo.createdDate || 'N/A'}`,
    `- **Supported in Code**: ${supportedVersions.map((v) => `\`${v}\``).join(', ')}`,
    `- **Status**: ${hasUpdate ? '⚠️ **New version detected!**' : '✅ **Up to date**'}`,
  ].join('\n');

  console.log('\n' + summary);
  appendGithubSummary(summary);

  if (hasUpdate) {
    console.log(`\nNew upstream version ${storeInfo.version} is not in Constants.kt!`);
    const issueBody = generateIssueBody(storeInfo, supportedVersions);
    fs.writeFileSync(ISSUE_BODY_FILE, issueBody, 'utf8');
    console.log(`Wrote issue body template to: ${ISSUE_BODY_FILE}`);
  } else {
    console.log('\nYouTube VR is at the latest supported version in Constants.kt.');
    if (fs.existsSync(ISSUE_BODY_FILE)) {
      fs.unlinkSync(ISSUE_BODY_FILE);
    }
  }
}

main().catch((err) => {
  console.error('Error checking upstream version:', err);
  process.exit(1);
});
