import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import http from 'node:http';
import { join, resolve } from 'node:path';
import test from 'node:test';
import { buildZip } from '../tools/fetch-local-art.mjs';

test('extracts scene and effect manifests from grouped ZIP ranges', async () => {
  const project = resolve(import.meta.dirname, '..');
  await mkdir(join(project, 'build'), { recursive: true });
  const temp = await mkdtemp(join(project, 'build', 'test-local-art-'));
  const source = join(temp, 'upstream');
  await mkdir(source);
  await writeFile(join(source, 'package.json'), JSON.stringify({ version: '0.3.0' }));

  const art = { count: 1, groups: { scene: { one: { path: '/assets/local/scene/one.png' } } } };
  const fx = { count: 1, groups: { fx: { spark: { path: '/assets/local/fx/spark.json' } } } };
  const entries = [
    ['data/local-assets.json', JSON.stringify(art)],
    ['data/local-fx.json', JSON.stringify(fx)],
    ['public/assets/local/scene/one.png', 'scene bytes'],
    ['public/assets/local/fx/spark.json', 'effect bytes'],
    ['public/assets/local/spine/unused.skel', 'excluded'],
  ].map(([name, data]) => ({ name: `Stronghold-Protocol/${name}`, data: Buffer.from(data) }));
  let archive = buildZip(entries);
  const server = http.createServer((request, response) => {
    const match = /^bytes=(\d+)-(\d+)$/.exec(request.headers.range || '');
    if (!match) { response.writeHead(416).end(); return; }
    const start = Number(match[1]);
    const end = Math.min(Number(match[2]), archive.length - 1);
    response.writeHead(206, {
      'content-range': `bytes ${start}-${end}/${archive.length}`,
      'content-length': end - start + 1,
    });
    response.end(archive.subarray(start, end + 1));
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const url = `http://127.0.0.1:${server.address().port}/source.zip`;
  const output = join(temp, 'local-art.zip');
  const run = () => new Promise((done) => {
    const child = spawn(process.execPath, ['tools/fetch-local-art.mjs', source, output], {
      cwd: project,
      env: { ...process.env, STRONGHOLD_LOCAL_ART_URL: url },
    });
    let log = '';
    child.stdout.on('data', (bytes) => { log += bytes; });
    child.stderr.on('data', (bytes) => { log += bytes; });
    child.once('close', (code) => done({ code, log }));
  });
  try {
    const success = await run();
    assert.equal(success.code, 0, success.log);
    assert.match(success.log, /合并为 \d+ 个范围请求/);
    const packed = await readFile(output);
    assert.ok(packed.includes(Buffer.from('data/local-fx.json')));
    assert.ok(packed.includes(Buffer.from('public/assets/local/fx/spark.json')));
    assert.ok(!packed.includes(Buffer.from('public/assets/local/spine/unused.skel')));

    const brokenFx = { count: 1, groups: { fx: { spark: { path: '/assets/local/fx/missing.json' } } } };
    entries[1].data = Buffer.from(JSON.stringify(brokenFx));
    archive = buildZip(entries);
    const failure = await run();
    assert.equal(failure.code, 1);
    assert.match(failure.log, /references an absent file/);
  } finally {
    server.close();
    await rm(temp, { recursive: true, force: true });
  }
});
