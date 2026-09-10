import { spawnSync } from 'node:child_process';
import { existsSync, rmSync } from 'node:fs';
import path from 'node:path';
// Build on the target OS. The application runtime is separate from the user's project JDK.
const jdk = process.env.JAVA_HOME;
if (!jdk) throw new Error('Set JAVA_HOME to a full JDK 17+ before packaging.');
const exe = path.join(jdk, 'bin', process.platform === 'win32' ? 'jlink.exe' : 'jlink');
if (!existsSync(exe)) throw new Error('JAVA_HOME must contain jlink.');
rmSync('resources/runtime', { recursive: true, force: true });
const r = spawnSync(exe, ['--add-modules', 'java.base,java.sql,java.logging,java.xml,java.net.http,java.management,jdk.httpserver,jdk.unsupported,jdk.crypto.ec', '--strip-debug', '--no-man-pages', '--no-header-files', '--output', 'resources/runtime'], { stdio: 'inherit' });
process.exit(r.status ?? 1);
