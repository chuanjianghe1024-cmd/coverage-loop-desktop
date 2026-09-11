import {readdirSync,statSync,writeFileSync,existsSync} from 'node:fs';
import path from 'node:path';
function files(dir){return existsSync(dir)?readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?files(path.join(dir,e.name)):[path.join(dir,e.name)]):[];}
const rows=files('package-artifacts').filter(p=>p.endsWith('.exe')).map(p=>({file:path.basename(p),bytes:statSync(p).size,MiB:Number((statSync(p).size/1024/1024).toFixed(2)),kind:p.includes('tauri')?'Tauri':'Electron'}));
const electron=rows.find(r=>r.kind==='Electron'),tauri=rows.find(r=>r.kind==='Tauri');
if(!electron||!tauri)throw new Error('Both installer artifacts are required for a comparison');
const reduction=Number(((1-tauri.bytes/electron.bytes)*100).toFixed(1));
const result={installers:rows,reductionPercent:reduction,note:'Both contain the same Java engine and jlink runtime. Tauri uses installed WebView2 or downloads it when missing.'};
writeFileSync('package-sizes.json',JSON.stringify(result,null,2)+'\n');
const text=`| 安装包 | MiB |\n| --- | ---: |\n${rows.map(r=>`| ${r.kind} | ${r.MiB} |`).join('\n')}\n\nTauri 安装包缩小 **${reduction}%**。不含缺失时下载的 WebView2。\n`;
writeFileSync('package-sizes.md',text);console.log(text);
if(process.env.GITHUB_STEP_SUMMARY)writeFileSync(process.env.GITHUB_STEP_SUMMARY,text,{flag:'a'});
