import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url));
const win = process.platform === 'win32';
const args = ['-f', 'backend/pom.xml', ...process.argv.slice(2)];
const result = spawnSync(win ? 'cmd.exe' : 'sh', win ? ['/d', '/c', 'mvnw.cmd', ...args] : ['mvnw', ...args], { cwd: root, stdio: 'inherit' });
if (result.error) console.error(result.error.message);
process.exit(result.status ?? 1);
