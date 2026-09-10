import { useEffect, useRef, useState, useMemo } from 'react';
import { AnimatePresence, motion } from 'motion/react';
import { Terminal, ChevronUp, ArrowDownToLine, Layers3, Check, X, Minus, Clock3, LoaderCircle } from 'lucide-react';
import type { LogEvent, ModuleProgress, Snapshot } from '@/lib/types';

const stages:Record<string,string>={waiting:'等待',scanning:'扫描',resolving:'解析依赖',resources:'资源',compile:'编译','test-compile':'编译测试',test:'测试',testing:'测试',package:'打包',proguard:'ProGuard',install:'安装',report:'覆盖率报告',done:'完成'};
export function RunningWave({running}:{running:boolean}) {return <span className={'running-wave '+(running?'active':'')} aria-hidden="true"><i/><i/><i/><i/></span>;}
function elapsed(start:string|undefined,end:string|undefined,now:number) {
  if(!start)return '—';const s=Math.max(0,Math.floor(((end?Date.parse(end):now)-Date.parse(start))/1000));
  return s<60?`${s}s`:`${Math.floor(s/60)}m ${s%60}s`;
}
export function ModuleTimeline({modules,now}:{modules:ModuleProgress[];now:number}) {
  return <div className="module-timeline">{['pre-install','coverage'].map(phase=>{const items=modules.filter(m=>m.phase===phase);if(!items.length)return null;const done=items.filter(m=>['success','failed','skipped'].includes(m.status)).length;
    return <section key={phase}><div className="module-phase"><strong>{phase==='pre-install'?'01 / 依赖预构建':'02 / 范围测试与覆盖率'}</strong><span>{done} / {items.length} 个模块已结束</span></div>{items.map(m=><div className={'module-step '+m.status} key={m.key}><span className="module-state-icon">{m.status==='success'?<Check size={13}/>:m.status==='failed'?<X size={13}/>:m.status==='skipped'?<Minus size={13}/>:m.status==='running'?<LoaderCircle className="spin" size={13}/>:<span/>}</span><span className="module-step-name" title={m.modulePath}>{m.name}<small>{m.dependency?'依赖模块':m.modulePath}</small></span><span className="lifecycle-stage">{m.status==='success'?'已完成':m.status==='failed'?'失败':m.status==='skipped'?(m.stage==='no-tests'?'无测试':'已跳过'):stages[m.stage]??m.stage}</span><time>{elapsed(m.startedAt,m.finishedAt,now)}</time></div>)}</section>;
  })}</div>;
}
function readable(text:string) {
  return text.replace(/\u001b\[[0-?]*[ -/]*[@-~]/g,'').split('\n').map(line=>{
    try{const event=JSON.parse(line);if(event.part?.text)return event.part.text;if(event.type&&event.sessionID)return `[${event.type}] ${event.sessionID}`;}catch{/* Plain Maven / Agent output. */}return line;
  }).join('\n');
}
export function RuntimeDock({snapshot,logs}:{snapshot:Snapshot;logs:LogEvent[]}) {
  const [open,setOpen]=useState(false),[tab,setTab]=useState<'logs'|'modules'>('logs'),[follow,setFollow]=useState(true),[now,setNow]=useState(Date.now());
  const scroller=useRef<HTMLDivElement>(null);const running=['running','stopping'].includes(snapshot.status);
  const modules=snapshot.progress?.modules??snapshot.latest?.moduleProgress??[];
  const active=modules.filter(m=>m.status==='running'),done=modules.filter(m=>['success','failed','skipped'].includes(m.status)).length;
  const content=useMemo(()=>readable(logs.filter(e=>e.type==='log').map(e=>e.data.text??'').join('')),[logs]);
  useEffect(()=>{document.documentElement.style.setProperty('--runtime-height',open?'350px':'52px');return()=>{document.documentElement.style.removeProperty('--runtime-height');};},[open]);
  useEffect(()=>{if(!running)return;const t=setInterval(()=>setNow(Date.now()),1000);return()=>clearInterval(t);},[running]);
  useEffect(()=>{if(follow&&open&&tab==='logs'&&scroller.current)scroller.current.scrollTop=scroller.current.scrollHeight;},[content,follow,open,tab]);
  const latestLog=logs.filter(e=>e.type==='log'&&e.data.stream!=='system').at(-1)?.data.timestamp;
  const quiet=latestLog?Math.max(0,Math.floor((now-Date.parse(latestLog))/1000)):null;
  return <motion.aside className={'runtime-dock '+(running?'is-running':'')} aria-label="运行控制台" animate={{height:open?350:52}} transition={{type:'spring',stiffness:320,damping:34}}>
    <button className="runtime-summary" onClick={()=>setOpen(v=>!v)} aria-expanded={open} aria-controls="runtime-body"><span className="runtime-terminal"><Terminal size={16}/></span><strong>运行控制台</strong><RunningWave running={running}/><span className="runtime-current">{running?(active.length?`${active[0].name} · ${stages[active[0].stage]??active[0].stage}${active.length>1?` · ${active.length} 个模块并行`:''}`:snapshot.progress?.message??snapshot.message):snapshot.id?snapshot.message:'等待下一次执行'}</span>{modules.length>0&&<span className="runtime-count">{done}/{modules.length} 模块</span>}<span className="runtime-time"><Clock3 size={12}/>{elapsed(snapshot.startedAt,snapshot.finishedAt,now)}</span><motion.span animate={{rotate:open?180:0}}><ChevronUp size={17}/></motion.span></button>
    <AnimatePresence initial={false}>{open&&<motion.div id="runtime-body" className="runtime-body" initial={{opacity:0}} animate={{opacity:1}} exit={{opacity:0}}><div className="runtime-toolbar"><div className="tabs" role="tablist" aria-label="控制台内容"><button role="tab" aria-selected={tab==='logs'} className={tab==='logs'?'selected':''} onClick={()=>setTab('logs')}><Terminal size={13}/>实时输出</button><button role="tab" aria-selected={tab==='modules'} className={tab==='modules'?'selected':''} onClick={()=>setTab('modules')}><Layers3 size={13}/>模块进度{modules.length>0&&<span>{done}/{modules.length}</span>}</button></div><span className="runtime-heartbeat">{running?(quiet!=null&&quiet>15?`进程运行中 · ${quiet}s 无新输出`:'持续接收执行状态'):'本次执行的实时输出'}{snapshot.progress?.round?` · R${String(snapshot.progress.round).padStart(2,'0')}`:''}</span>{tab==='logs'&&<button className={'runtime-follow '+(follow?'selected':'')} aria-pressed={follow} onClick={()=>setFollow(v=>!v)}><ArrowDownToLine size={13}/>自动跟随</button>}</div>
      {tab==='logs'?<div className="runtime-output" ref={scroller} role="log" aria-live="off">{snapshot.truncated&&<p>较早输出已省略，完整日志请查看本轮记录。</p>}<pre>{content||'启动基线、补测或统计后，Maven 与 Agent 的实时输出将在这里显示。'}</pre>{running&&follow&&<span className="terminal-cursor"/>}</div>:modules.length?<ModuleTimeline modules={modules} now={now}/>:<div className="runtime-empty">{running?'正在解析 Maven 工程，模块进入生命周期后会逐个显示。':'任务开始后，这里会显示每个模块的构建阶段和耗时。'}</div>}
    </motion.div>}</AnimatePresence>
  </motion.aside>;
}
