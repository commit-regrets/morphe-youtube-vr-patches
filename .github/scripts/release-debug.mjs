import { generateNotes } from '@semantic-release/release-notes-generator';
import { execSync } from 'node:child_process';
import fs from 'node:fs';

console.log('node:', process.version);
console.log('preset URL:', import.meta.resolve('conventional-changelog-conventionalcommits'));
console.log('writer URL from root:', import.meta.resolve('conventional-changelog-writer'));

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
  console.log('RENDER OK, first 2 lines:\n' + notes.split('\n').slice(0, 2).join('\n'));
} catch (e) {
  console.log('RENDER FAILED:');
  console.log(e.stack);
  process.exitCode = 1;
}
