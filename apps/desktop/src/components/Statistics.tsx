import { RoundRecords } from './RoundRecords';
import { useEffect, useState } from 'react';
import { AnimatePresence, motion, animate, useReducedMotion } from 'motion/react';
import { BarChart3, Save, Copy, Plus, Trash2, Play, Square, Folder, Package, FileCode2, FolderOpen, ArrowUpRight } from 'lucide-react';
import { api } from '@/lib/api';
import type { Config, OpenProject, Snapshot, StatisticsNode, TestSelection } from '@/lib/types';
import { ScopeTree, selectedClassCount, TreeBranch } from './ScopeTree';
import { PathField } from './Wizard';
import { Status } from './Dashboard';
import { BentoGrid, BentoGridItem } from './ui/bento-grid';
import { HoverBorderGradient } from './ui/hover-border-gradient';

function Counter({value,decimal=0}:{value:number;decimal?:number}) {
  const [shown,setShown]=useState(value); const reduced=useReducedMotion();
  useEffect(()=>{if(reduced){setShown(value);return;}const c=animate(0,value,{duration:.55,ease:'easeOut',onUpdate:setShown});return()=>c.stop();},[value,reduced]);
  return <span>{shown.toFixed(decimal)}</span>;
}
function Numbers({node}:{node:StatisticsNode}) {
  return <span className="tree-metrics"><span>{node.measured?`${node.coveredLines} 行 / ${node.totalLines} 行`:'— 行 / — 行'}</span><span className="statistics-track"><motion.i initial={{width:0}} animate={{width:`${node.lineCoverage??0}%`}} transition={{duration:.5}}/></span><strong>{node.lineCoverage==null?(node.measured?'—':'未测'):`${node.lineCoverage.toFixed(2)}%`}</strong></span>;
}
export function StatisticsResult({node}:{node:StatisticsNode}) {
  const Icon=node.kind==='module'?Package:node.kind==='package'?Folder:node.kind==='class'?FileCode2:BarChart3;
  const label=<><Icon size={15}/><span className="tree-name" title={node.name}>{node.name}</span><Numbers node={node}/></>;
  return node.children.length?<TreeBranch name={node.name} label={label} initialOpen={node.kind==='project'||node.kind==='module'}>{node.children.map(c=><StatisticsResult key={c.id} node={c}/>)}</TreeBranch>:<div className="tree-row tree-leaf">{label}</div>;
}
function fresh(base:Config):Config {
  return {...structuredClone(base),id:crypto.randomUUID(),name:'新的统计配置',selectedModulePaths:[],scopes:[],agent:{...base.agent,enabled:false},maven:{...base.maven,testPattern:''}};
}
export function Statistics({data,base,snapshot,historical,onStart,onStop,busy,notify}:{data:OpenProject;base:Config;snapshot:Snapshot;historical:Snapshot|null;onStart:(c:Config)=>Promise<void>;onStop:()=>Promise<void>;busy:boolean;notify:(s:string)=>void}) {
  const [config,setConfig]=useState<Config>(()=>fresh(base)),[configs,setConfigs]=useState<Config[]>([]);
  const [preview,setPreview]=useState<TestSelection[]>([]),[loading,setLoading]=useState(false),[ready,setReady]=useState(false),[savedPath,setSavedPath]=useState('');
  const [feedback,setFeedback]=useState('');
  useEffect(()=>{let active=true;api.request<Config[]>('/statistics/configs',{rootPomPath:base.rootPomPath}).then(items=>{if(!active)return;setConfigs(items);if(items.length)setConfig(items[0]);setReady(true);}).catch(e=>notify(String(e)));return()=>{active=false;};},[base.rootPomPath]);
  useEffect(()=>{let active=true;const timer=setTimeout(()=>{api.request<TestSelection[]>('/statistics/preview',{config}).then(v=>{if(active)setPreview(v);}).catch(e=>{if(active){setPreview([]);notify(String(e));}});},250);return()=>{active=false;clearTimeout(timer);};},[config]);
  const current=historical??(snapshot.mode==='statistics'?snapshot:null);
  const result=current?.statistics;
  const change=(next:Config)=>{setConfig(next);setSavedPath('');setFeedback('');};
  const run=async(action:()=>Promise<void>)=>{setLoading(true);try{await action();}catch(e){notify(e instanceof Error?e.message:String(e));}finally{setLoading(false);}};
  const save=async(copy=false)=>{
    const saved=copy?{...config,id:crypto.randomUUID(),name:config.name+' · 副本'}:config;
    const r=await api.request<{configs:Config[];path:string}>('/statistics/save',{config:saved});setConfig(saved);setConfigs(r.configs);setSavedPath(r.path);setFeedback(copy?'已另存为独立配置':'统计配置已保存');
  };
  return <section className="statistics-page"><div className="page-title"><div><div className="eyebrow">COVERAGE EXPLORER</div><h1>从模块，看到每一行。</h1><p>选择范围，运行测试，按模块、包和类查看真实行覆盖率。</p></div><span className="subtle-badge">独立统计 · 多配置</span></div>
    <fieldset className="wizard-fieldset" disabled={busy||loading||!ready}><div className="panel statistics-config"><div className="statistics-config-header"><label className="field"><span>已保存的统计配置</span><select aria-label="已保存的统计配置" value={configs.some(c=>c.id===config.id)?config.id:''} onChange={e=>{const c=configs.find(c=>c.id===e.target.value);if(c)change(structuredClone(c));}}><option value="" disabled>尚未保存</option>{configs.map(c=><option value={c.id} key={c.id}>{c.name}</option>)}</select></label><label className="field"><span>统计配置名称</span><input value={config.name} onChange={e=>change({...config,name:e.target.value})}/></label><div className="button-group"><button className="secondary" onClick={()=>change(fresh(base))}><Plus size={15}/>新建</button><button className="secondary" onClick={()=>void run(()=>save(true))} disabled={!config.name.trim()}><Copy size={15}/>另存为</button><button className="primary" onClick={()=>void run(()=>save())} disabled={!config.name.trim()}><Save size={15}/>保存配置</button><button className="icon-button" aria-label="删除统计配置" disabled={!configs.some(c=>c.id===config.id)} onClick={()=>void run(async()=>{const remaining=await api.request<Config[]>('/statistics/delete',{rootPomPath:base.rootPomPath,id:config.id});setConfigs(remaining);change(remaining[0]??fresh(base));setFeedback('配置已删除，历史结果仍可查看');})}><Trash2 size={15}/></button></div></div>
    <div className="form-grid"><PathField label="统计 Maven settings.xml" value={config.maven.settingsPath} onChange={s=>change({...config,maven:{...config.maven,settingsPath:s}})} kind="settings" placeholder="沿用默认 settings"/><PathField label="统计本地 Maven 仓库" value={config.maven.localRepository} onChange={s=>change({...config,maven:{...config.maven,localRepository:s}})} kind="repository" placeholder="D:/m2" hint="短仓库路径有助于解决 ProGuard 流程中的命令行过长问题。"/></div>
    <details className="statistics-options"><summary>构建环境与预构建</summary><div className="form-grid"><PathField label="统计目标 JDK" value={config.maven.javaHome} onChange={s=>change({...config,maven:{...config.maven,javaHome:s}})} kind="jdk"/><PathField label="统计 Maven 程序" value={config.maven.executable} onChange={s=>change({...config,maven:{...config.maven,executable:s}})} kind="maven"/><label className="field"><span>统计 Maven Profiles</span><input value={config.maven.profiles.join(',')} onChange={e=>change({...config,maven:{...config.maven,profiles:e.target.value.split(',').map(v=>v.trim()).filter(Boolean)}})}/></label><label className="field"><span>统计 Maven 额外参数（一行一个）</span><textarea value={config.maven.extraArgs.join('\n')} onChange={e=>change({...config,maven:{...config.maven,extraArgs:e.target.value.split('\n')}})}/></label></div><label className="toggle-row"><span>运行前 install 所选模块及其依赖（跳过测试）</span><input type="checkbox" checked={config.maven.preInstall} onChange={e=>change({...config,maven:{...config.maven,preInstall:e.target.checked}})}/></label></details>
    {feedback&&<div className="config-feedback" role="status">{feedback}{savedPath&&<button className="ghost small" onClick={()=>void api.openArtifact(savedPath).catch(e=>notify(String(e)))}><FolderOpen size={14}/>打开配置文件</button>}</div>}</div>
    <div className="section-heading"><div><h2>统计范围</h2><p>直接勾选模块、包和类。测试按所选类所在包自动筛选，覆盖率只统计勾选的类。</p></div><div className="button-group"><button className="secondary small" onClick={()=>change({...config,selectedModulePaths:data.project.modules.filter(m=>m.hasMainSources).map(m=>m.relativePath),scopes:[]})}>全选</button><button className="ghost small" onClick={()=>change({...config,selectedModulePaths:[],scopes:[]})}>清空</button></div></div><ScopeTree modules={data.project.modules} sources={data.sources} config={config} onChange={change}/>
    <div className="statistics-execution"><div><strong>{selectedClassCount(config,data.sources)} 个生产类 · {preview.reduce((n,m)=>n+m.tests.length,0)} 个测试文件</strong><p>依赖模块按需预构建；测试逐个选中模块执行。类与测试无法静态一一对应，因此同包测试共同参与测量。</p>{preview.filter(m=>!m.tests.length).map(m=><p className="inline-warning" key={m.modulePath}>{m.modulePath} 未找到所选包中的测试，可能无法生成覆盖率报告。</p>)}</div><HoverBorderGradient onClick={()=>void run(async()=>{await onStart(config);setConfigs(await api.request<Config[]>('/statistics/configs',{rootPomPath:base.rootPomPath}));setFeedback('统计配置已保存，任务已启动');})} containerClassName="primary-gradient" className="gradient-content" disabled={!selectedClassCount(config,data.sources)}><Play size={15}/>运行覆盖率统计</HoverBorderGradient></div></fieldset>
    {busy&&<div className="statistics-running"><motion.span className="running-spinner" animate={{rotate:360}} transition={{repeat:Infinity,duration:1.4,ease:'linear'}}><BarChart3 size={17}/></motion.span><span>{snapshot.progress?.message??snapshot.message}</span><button className="secondary" onClick={()=>void run(onStop)}><Square size={13}/>停止任务</button></div>}
    <div className="section-heading statistics-result-heading"><div><h2>行覆盖率结果</h2><p>{current?`${historical?'历史结果':'最近运行'} · ${current.configName??config.name} · ${current.finishedAt?new Date(current.finishedAt).toLocaleString():'进行中'}`:'完成一次统计后，在这里逐层查看结果。'}</p></div>{current&&<Status value={current.status}/>}</div>
    <AnimatePresence mode="wait">{result?<motion.div key={current?.id} initial={{opacity:0,y:10}} animate={{opacity:1,y:0}} exit={{opacity:0}}><BentoGrid className="statistics-metrics"><BentoGridItem title={<><Counter value={result.classCount}/> <small>个类</small></>} description="本次统计范围" icon={<FileCode2 size={19}/>}/><BentoGridItem title={result.measured?<><Counter value={result.coveredLines}/> / {result.totalLines} <small>行</small></>:'等待完整报告'} description="已覆盖行数 / 可执行总行数" icon={<BarChart3 size={19}/>}/><BentoGridItem title={result.lineCoverage==null?'—':<><Counter value={result.lineCoverage} decimal={2}/><small>%</small></>} description="按行数加权汇总" icon={<ArrowUpRight size={19}/>}/></BentoGrid><div className="tree-panel statistics-result"><div className="tree-toolbar"><strong>模块 / 包 / 类</strong><span>已覆盖行 / 总行 · 行覆盖率</span></div><StatisticsResult node={result}/></div><p className="metrics-note">行数来自 JaCoCo 可执行行计数。缺失或过期报告显示“未测”；无可执行行显示“—”。运行结果与配置分别保存，修改配置不会改写历史结果。</p></motion.div>:<motion.div className="statistics-empty panel" initial={{opacity:0}} animate={{opacity:1}}><BarChart3 size={32}/><h3>{current?.status==='running'?'正在收集覆盖率':'让覆盖率清晰可见'}</h3><p>选择模块与包，运行一次统计。每个节点都会显示覆盖行数、总行数和百分比。</p></motion.div>}</AnimatePresence>
    {current&&<RoundRecords rootPomPath={base.rootPomPath} jobId={current.id} round={current.latest??current.rounds.at(-1)} busy={busy} notify={notify}/>}
  </section>;
}
