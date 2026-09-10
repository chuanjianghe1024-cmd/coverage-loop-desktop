import { request } from 'node:https';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { HttpsProxyAgent } from 'https-proxy-agent';
const root=fileURLToPath(new URL('../',import.meta.url));
const desktop=path.join(root,'apps/desktop');
// Automatic bootstrap fetches public components without credentials.
const registry='https://ui.aceternity.com/registry/';
const pins=JSON.parse(readFileSync(path.join(root,'scripts/aceternity-lock.json'),'utf8'));
const proxy=process.env.HTTPS_PROXY||process.env.https_proxy;
const agent=proxy?new HttpsProxyAgent(proxy,{timeout:20000}):undefined;
function get(url) {
 return new Promise((resolve,reject)=>{
  let deadline;
 const req=request(url,{agent},response=>{
   if(response.statusCode!==200){response.resume();reject(new Error('Aceternity registry returned HTTP '+response.statusCode));return;}
   const chunks=[];let size=0;
   response.on('data',chunk=>{size+=chunk.length;if(size>500000){response.destroy();reject(new Error('Component response too large'));}else chunks.push(chunk);});
   response.on('end',()=>{try{resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));}catch{reject(new Error('Invalid component registry response'));}});
  });
  req.on('close',()=>clearTimeout(deadline));
  deadline=setTimeout(()=>{req.destroy();reject(new Error('Aceternity registry connection timed out'));},30000);
  req.on('error',()=>reject(new Error('Cannot reach Aceternity registry. Check the network/proxy and run npm run ui:sync again.')));
  req.setTimeout(60000,()=>{req.destroy();reject(new Error('Aceternity registry timed out'));});req.end();
 });
}
for(const name of ['bento-grid','hover-border-gradient']) {
 const item=await get(registry+name+'.json');
 const file=item.files?.find(f=>f.path===`components/ui/${name}.tsx`);
 if(typeof file?.content!=='string')throw new Error('Registry did not return '+name);
 const hash=createHash('sha256').update(file.content).digest('hex');
 if(hash!==pins[name])throw new Error('Upstream '+name+' changed. Review the new official source and update scripts/aceternity-lock.json before syncing.');
 let source=file.content;
 if(name==='hover-border-gradient') source=source.replace('useEffect, useRef','useEffect').replace('import { motion }','import { motion, useReducedMotion }').replace('  const [hovered,','  const reducedMotion = useReducedMotion();\n  const [hovered,').replace('if (!hovered) {','if (!hovered && !reducedMotion) {').replace('}, [hovered]);','}, [hovered, duration, clockwise, reducedMotion]);');
 if(name==='hover-border-gradient') source=source.replace('clockwise?: boolean;', 'clockwise?: boolean;\n    disabled?: boolean;').replaceAll('#3275F8','#7cb986').replaceAll('hsl(0, 0%, 100%)','#91bc7b');
 const directory=path.join(desktop,'src/components/ui');mkdirSync(directory,{recursive:true});
 writeFileSync(path.join(directory,name+'.tsx'),`// Local Aceternity component. Downloaded by npm run ui:sync.\n${source}`);
 console.log('Synced Aceternity UI: '+name);
}
