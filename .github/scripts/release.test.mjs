import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { prepareBundle, taggedCommit } from './release.mjs';

test('a first release checks for a missing tag without requesting an unknown commit', () => {
  const requests = [];
  assert.equal(taggedCommit('owner/repo', 'dev-1-123abcd', endpoint => {
    requests.push(endpoint);
    return null;
  }), null);
  assert.deepEqual(requests, ['repos/owner/repo/git/ref/tags/dev-1-123abcd']);
});

test('annotated tags resolve to the underlying commit before publication', () => {
  assert.deepEqual(taggedCommit('owner/repo', 'v0.2.0', endpoint =>
    endpoint.includes('/git/ref/') ? { object: { type: 'tag', sha: 'tag-object' } } : { sha: 'commit-object' }),
  { sha: 'commit-object' });
});

const devFiles = [
  'windows/app-debug.apk', 'windows/Aegis-Remote-Desktop-Setup-0.2.0.exe',
  'linux/aegis-remote-desktop_0.2.0_amd64.deb', 'linux/aegis-remote-desktop-0.2.0-1.x86_64.rpm',
  'windows/bom.json', 'windows/bom.xml', 'windows/app-release-unsigned.apk', 'reports/test.html',
];

async function fixture(t, names = devFiles) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'aegis-release-test-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  for (const name of names) {
    const file = path.join(root, 'input', name);
    await mkdir(path.dirname(file), { recursive: true });
    await writeFile(file, name);
  }
  return { kind: 'development', tag: 'dev-42-123abcd', input: path.join(root, 'input'), output: path.join(root, 'output') };
}

test('development release selects only installable packages and inventories, with complete checksums', async t => {
  const options = await fixture(t);
  const bundle = await prepareBundle(options);
  assert.equal(bundle.assets.length, 7);
  assert.equal((await readdir(options.output)).length, 7);
  assert.ok(!bundle.assets.some(name => /unsigned|test\.html/.test(name)));
  for (const line of bundle.checksums.trim().split('\n')) {
    const [hash, name] = line.split('  ');
    assert.equal(hash, createHash('sha256').update(await readFile(path.join(options.output, name))).digest('hex'));
  }
});

test('missing Windows installer stops publication', async t => {
  const options = await fixture(t, devFiles.filter(name => !name.endsWith('.exe')));
  await assert.rejects(prepareBundle(options), /Expected exactly one/);
});

test('duplicate APKs stop publication', async t => {
  const options = await fixture(t, [...devFiles, 'extra/app-debug.apk']);
  await assert.rejects(prepareBundle(options), /found 2/);
});

test('invalid release tags and stale output are rejected', async t => {
  const options = await fixture(t);
  await assert.rejects(prepareBundle({ ...options, tag: 'v0.2.0' }), /Invalid release tag/);
  await mkdir(options.output);
  await writeFile(path.join(options.output, 'old.exe'), 'stale');
  await assert.rejects(prepareBundle(options), /must be empty/);
});

test('Linux package versions must match the Windows package', async t => {
  const options = await fixture(t, devFiles.map(name => name.replace('0.2.0_amd64', '0.3.0_amd64')));
  await assert.rejects(prepareBundle(options), /Expected exactly one/);
});

test('signed release requires matching version and original checksums', async t => {
  const names = [
    'Aegis-Remote-Android-0.2.0.apk', 'Aegis-Remote-Desktop-Setup-0.2.0.exe',
    'aegis-sbom-0.2.0.json', 'aegis-sbom-0.2.0.xml', 'verify-windows-update.ps1',
  ];
  const options = { ...await fixture(t, names), kind: 'signed', tag: 'v0.2.0' };
  const checksums = names.sort().map(name => `${createHash('sha256').update(name).digest('hex')}  ${name}`).join('\n') + '\n';
  await writeFile(path.join(options.input, 'SHA256SUMS.txt'), checksums.replaceAll('\n', '\r\n'));
  await assert.rejects(prepareBundle({ ...options, tag: 'v0.3.0' }), /version differ/);
  await writeFile(path.join(options.input, names[0]), 'tampered');
  await assert.rejects(prepareBundle(options), /checksum verification failed/);
  await writeFile(path.join(options.input, names[0]), names[0]);
  assert.equal((await prepareBundle(options)).assets.length, 6);
});
