import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { createReadStream } from 'node:fs';
import { copyFile, mkdir, readdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

async function filesIn(root) {
  const result = [];
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const file = path.join(root, entry.name);
    if (entry.isSymbolicLink()) throw new Error(`Unexpected symbolic link: ${file}`);
    if (entry.isDirectory()) result.push(...await filesIn(file));
    else if (entry.isFile()) result.push(file);
  }
  return result;
}

async function digest(file) {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(file)) hash.update(chunk);
  return hash.digest('hex');
}

export async function prepareBundle({ kind, tag, input, output }) {
  if (!['development', 'signed'].includes(kind)) throw new Error('Invalid release kind');
  const tagPattern = kind === 'signed' ? /^v\d+\.\d+\.\d+$/ : /^dev-\d+-[a-f0-9]{7}$/;
  if (!tagPattern.test(tag)) throw new Error('Invalid release tag');
  const files = await filesIn(input);
  const one = (pattern) => {
    const matches = files.filter(file => pattern.test(path.basename(file)));
    if (matches.length !== 1) throw new Error(`Expected exactly one ${pattern}; found ${matches.length}`);
    return matches[0];
  };
  const setup = one(/^Aegis-Remote-Desktop-Setup-\d+\.\d+\.\d+\.exe$/);
  const version = path.basename(setup).match(/(\d+\.\d+\.\d+)\.exe$/)[1];
  if (kind === 'signed' && tag !== `v${version}`) throw new Error('Tag and package version differ');
  const escapedVersion = version.replaceAll('.', '\\.');
  const entries = [[setup, path.basename(setup)]];
  if (kind === 'development') {
    entries.push(
      [one(/^app-debug\.apk$/), `Aegis-Android-${version}-dev.apk`],
      [one(new RegExp(`^aegis-remote-desktop_${escapedVersion}_amd64\\.deb$`)), `aegis-remote-desktop_${version}_amd64.deb`],
      [one(new RegExp(`^aegis-remote-desktop-${escapedVersion}-1\\.x86_64\\.rpm$`)), `aegis-remote-desktop-${version}-1.x86_64.rpm`],
      [one(/^bom\.json$/), `aegis-sbom-${version}.json`],
      [one(/^bom\.xml$/), `aegis-sbom-${version}.xml`],
    );
  } else {
    for (const name of [
      `Aegis-Remote-Android-${version}.apk`, 'verify-windows-update.ps1',
      `aegis-sbom-${version}.json`, `aegis-sbom-${version}.xml`,
    ]) {
      const matches = files.filter(file => path.basename(file) === name);
      if (matches.length !== 1) throw new Error(`Expected exactly one ${name}`);
      entries.push([matches[0], name]);
    }
    if (files.length !== 6) throw new Error('Signed bundle must contain exactly six files');
  }
  entries.sort((a, b) => a[1] < b[1] ? -1 : a[1] > b[1] ? 1 : 0);
  const checksums = (await Promise.all(entries.map(async ([file, name]) => `${await digest(file)}  ${name}`))).join('\n') + '\n';
  if (kind === 'signed') {
    const original = (await readFile(one(/^SHA256SUMS\.txt$/), 'utf8')).replaceAll('\r\n', '\n');
    if (original !== checksums) throw new Error('Signed bundle checksum verification failed');
  }
  await mkdir(output, { recursive: true });
  if ((await readdir(output)).length) throw new Error('Release output directory must be empty');
  for (const [file, name] of entries) await copyFile(file, path.join(output, name));
  await writeFile(path.join(output, 'SHA256SUMS.txt'), checksums);
  return { version, checksums, assets: [...entries.map(([, name]) => name), 'SHA256SUMS.txt'] };
}

function gh(...args) {
  return execFileSync('gh', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

function apiOrMissing(endpoint) {
  try { return JSON.parse(gh('api', endpoint)); }
  catch (error) {
    if (String(error.stderr).includes('(HTTP 404)')) return null;
    throw error;
  }
}

function releaseOrMissing(repository, tag) {
  try {
    return JSON.parse(gh('release', 'view', tag, '--repo', repository,
      '--json', 'isDraft,isPrerelease,url,assets'));
  } catch (error) {
    if (String(error.stderr).trim() === 'release not found') return null;
    throw error;
  }
}

async function publish() {
  const { RELEASE_KIND: kind, RELEASE_TAG: tag, GITHUB_REPOSITORY: repository,
    GITHUB_SHA: sha, GITHUB_RUN_ID: runId, GITHUB_SERVER_URL: server = 'https://github.com' } = process.env;
  if (!/^[\w.-]+\/[\w.-]+$/.test(repository ?? '') || !/^[a-f0-9]{40}$/.test(sha ?? '') || !/^\d+$/.test(runId ?? '')) {
    throw new Error('Missing or invalid immutable workflow provenance');
  }
  const output = 'release-assets';
  const bundle = await prepareBundle({ kind, tag, input: 'release-input', output });
  const existing = releaseOrMissing(repository, tag);
  const taggedCommit = apiOrMissing(`repos/${repository}/commits/${tag}`);
  if (taggedCommit && taggedCommit.sha !== sha) throw new Error('Existing tag points to another commit');
  if (kind === 'signed' && !taggedCommit) throw new Error('Signed release requires an existing tag');
  if (existing && !existing.isDraft) {
    const manifest = gh('release', 'download', tag, '--repo', repository, '--pattern', 'SHA256SUMS.txt', '--output', '-');
    if (manifest !== bundle.checksums.trim() || existing.isPrerelease !== (kind === 'development') ||
        existing.assets.map(asset => asset.name).sort().join('\n') !== [...bundle.assets].sort().join('\n')) {
      throw new Error('Published release differs; refusing to overwrite it');
    }
    console.log(`Already published and unchanged: ${existing.url}`);
    return;
  }
  const runUrl = `${server}/${repository}/actions/runs/${runId}`;
  const notes = [
    kind === 'development' ? '## Development pre-release' : '## Signed release',
    '', `Source commit: ${sha}`, `Build and validation: ${runUrl}`, '',
    ...(kind === 'development' ? [
      'Automated evaluation build, not a production release.',
      '- Android: installable debug-signed APK, package dev.aegis.remote.android.dev. The CI debug key can change between builds; updating may require uninstalling the previous development app and losing its local data.',
      '- Windows: unsigned Setup with bundled OpenSSH. Windows may display an unknown-publisher warning.',
      '- Linux: DEB and RPM packages, without a production signature.',
      '- Package version: ' + bundle.version + '. The release tag and source commit identify this build.',
      '- Physical Android-to-PC, installer and remote-network release matrices remain pending.',
    ] : ['Android and Windows signatures and installer smoke checks passed in the linked workflow.']),
    '', 'Assets include CycloneDX runtime dependency inventories and SHA256SUMS.txt.',
    'Checksums establish file integrity; they do not replace publisher signature verification.',
    `Release requirements: ${server}/${repository}/blob/${sha}/remote-control-kmp/docs/RELEASE.md`, '',
  ].join('\n');
  await writeFile('release-notes.md', notes);
  if (!existing) {
    const flags = kind === 'development' ? ['--prerelease'] : ['--verify-tag'];
    gh('release', 'create', tag, '--repo', repository, '--target', sha, '--draft', '--latest=false',
      '--title', kind === 'development' ? `AegisLink ${tag}` : `AegisLink ${tag.slice(1)}`,
      '--notes-file', 'release-notes.md', ...flags);
  }
  gh('release', 'upload', tag, '--repo', repository, '--clobber', ...bundle.assets.map(name => path.join(output, name)));
  const uploaded = JSON.parse(gh('release', 'view', tag, '--repo', repository, '--json', 'assets'));
  if (uploaded.assets.map(asset => asset.name).sort().join('\n') !== [...bundle.assets].sort().join('\n')) {
    throw new Error('Draft assets differ from the complete bundle; refusing publication');
  }
  gh('release', 'edit', tag, '--repo', repository, '--draft=false', `--prerelease=${kind === 'development'}`,
    `--latest=${kind === 'signed'}`, '--notes-file', 'release-notes.md');
  const published = JSON.parse(gh('release', 'view', tag, '--repo', repository, '--json', 'url,isDraft,assets'));
  if (published.isDraft || published.assets.map(asset => asset.name).sort().join('\n') !== [...bundle.assets].sort().join('\n')) {
    throw new Error('Published release readback does not match the complete bundle');
  }
  console.log(`Published ${bundle.assets.length} assets: ${published.url}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  await publish();
}
