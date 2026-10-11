import { generateNotes } from '@semantic-release/release-notes-generator';
import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';

const require = createRequire(import.meta.url);
const presetPkg = require.resolve('conventional-changelog-conventionalcommits/package.json');
console.log('preset pkg:', presetPkg);
console.log('preset version:', require(presetPkg).version);

const presetRequire = createRequire(presetPkg);
try {
  const w = presetRequire.resolve('conventional-changelog-writer/package.json');
  console.log('preset->writer:', presetRequire(w).version, 'at', w);
} catch (e) {
  console.log('preset->writer resolve FAILED:', e.message.split('\n')[0]);
}
console.log('node:', process.version);

const raw = execSync('git log v1.22.1..HEAD --pretty=format:%H%x00%s%x00%b%x1e').toString();
const commits = raw.split('\u001e').filter(Boolean).map(r => {
  const [hash, subject, body] = r.split('\u0000');
  return { hash, key: hash.slice(0, 8), message: subject + (body ? '\n\n' + body : ''),
           subject, header: subject, body: body || '', footer: null,
           mentions: [], notes: [], committerDate: new Date().toISOString() };
});

const pluginConfig = {
  preset: 'conventionalcommits',
  presetConfig: { types: [
    { type: 'fix', section: 'Bug Fixes', hidden: false },
    { type: 'feat', section: 'New Features', hidden: false },
    { type: 'bump', section: 'Updated App Support', hidden: false },
    { type: 'perf', section: 'Improvements', hidden: false },
    { type: 'build', hidden: true },
  ] },
};

try {
  const notes = await generateNotes(pluginConfig, {
    options: { repositoryUrl: 'https://github.com/commit-regrets/morphe-youtube-vr-patches.git' },
    branch: { type: 'release' },
    commits,
    releases: [],
    lastRelease: { version: '1.22.1', gitTag: 'v1.22.1', gitHead: 'e96c8d6' },
    nextRelease: { type: 'patch', version: '1.22.2', gitTag: 'v1.22.2' },
  });
  console.log('RENDER OK, first 3 lines:\n' + notes.split('\n').slice(0, 3).join('\n'));
} catch (e) {
  console.log('RENDER FAILED:', e.message.split('\n')[0]);
  process.exitCode = 1;
}
