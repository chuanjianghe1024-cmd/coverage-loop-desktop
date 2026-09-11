import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root=fileURLToPath(new URL('../',import.meta.url));
const mode=process.argv[2]??'dev';
if(!['dev','build'].includes(mode))throw new Error('Usage: node scripts/tauri.mjs dev|build');
function run(script,args=[],cwd=root){
  const result=spawnSync(process.execPath,[script,...args],{cwd,stdio:'inherit'});
  if(result.error)throw result.error;
  if(result.status!==0)process.exit(result.status??1);
}
run(path.join(root,'scripts/backend.mjs'),['package','-DskipTests']);
// Also stage resources for tauri-build; debug execution still uses the developer's JDK.
run(path.join(root,'scripts/runtime.mjs'));
if(process.platform==='win32'){
  const prepared=spawnSync('powershell.exe',['-NoProfile','-File',path.join(root,'scripts/tauri-runtime.ps1')],{cwd:root,stdio:'inherit'});
  if(prepared.error)throw prepared.error;
  if(prepared.status!==0)process.exit(prepared.status??1);
}
run(path.join(root,'node_modules/@tauri-apps/cli/tauri.js'),[mode,...process.argv.slice(3)],path.join(root,'apps/desktop'));
