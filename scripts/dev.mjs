import { spawn, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const build = spawnSync(process.execPath, ['scripts/backend.mjs', 'package', '-DskipTests'], { stdio: 'inherit' });
if (build.status !== 0) process.exit(build.status ?? 1);
const vite = spawn(process.execPath, ['../../node_modules/vite/bin/vite.js', '--host', '127.0.0.1'], { cwd: 'apps/desktop', stdio: 'inherit' });
let electron;
let stopping = false;
function stop() { if (stopping) return; stopping = true; electron?.kill(); vite.kill(); }
process.on('SIGINT', stop); process.on('SIGTERM', stop);
vite.on('exit', () => { if (!stopping) { stop(); process.exitCode = 1; } });
try {
  const deadline = Date.now() + 30000;
  while (true) {
    try { if ((await fetch('http://127.0.0.1:5173')).ok) break; } catch {}
    if (Date.now() > deadline) throw new Error('Vite startup timed out');
    await new Promise(r => setTimeout(r, 250));
  }
  electron = spawn(require('electron'), ['apps/desktop'], { stdio: 'inherit', env: { ...process.env, COVERAGE_DEV_URL: 'http://127.0.0.1:5173' } });
  electron.on('exit', code => { stop(); process.exitCode = code ?? 0; });
} catch (error) { console.error(error.message); stop(); process.exitCode = 1; }
