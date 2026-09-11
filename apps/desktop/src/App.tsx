import { Titlebar } from './components/Titlebar';
import { HistoryList } from './components/HistoryList';
import type { MavenVersionSettings } from './components/MavenVersionFields';
import { RuntimeDock } from './components/RuntimeDock';
import { useState, useEffect, useRef } from 'react';
import { motion } from 'motion/react';
import { Statistics } from './components/Statistics';
import { BarChart3, LayoutDashboard, FolderGit2, History, ArrowUpRight, Plus, Settings2, X, Database, CircleHelp } from 'lucide-react';
import { api, isDesktop } from './lib/api';
import type { OpenProject, Config, RecentProject, Snapshot, LogEvent, JobSummary } from './lib/types';
import { Wizard } from './components/Wizard';
import { Dashboard } from './components/Dashboard';
const idle:Snapshot={id:'',status:'idle',mode:'',message:'工作区已准备就绪',rounds:[],events:[],cursor:0};
export default function App() {
  const [page,setPage]=useState<'wizard'|'dashboard'|'history'|'statistics'>('wizard');
  const [data,setData]=useState<OpenProject|null>(null),[config,setConfig]=useState<Config|null>(null);
  const [recents,setRecents]=useState<RecentProject[]>([]),[jobs,setJobs]=useState<JobSummary[]>([]);
  const [snapshot,setSnapshot]=useState<Snapshot>(idle),[historical,setHistorical]=useState<Snapshot|null>(null);
  const [logs,setLogs]=useState<LogEvent[]>([]),[error,setError]=useState(''),[toast,setToast]=useState('');
  const [wizardStep,setWizardStep]=useState<1|2>(1);
  const [wizardKey,setWizardKey]=useState(0),[connection,setConnection]=useState(isDesktop?'connected':'preview');
  const cursor=useRef(0),job=useRef('');
  const deletedJobs=useRef(new Set<string>());
  const busy=['running','stopping'].includes(snapshot.status);
  const notify=(s:string)=>setError(s.replace(/^Error: /,''));
  useEffect(()=>{if(isDesktop)api.request<RecentProject[]>('/projects').then(setRecents).catch(e=>notify(String(e)));},[]);
  useEffect(()=>{
    if(!isDesktop)return;
    let alive=true,timer:ReturnType<typeof setTimeout>;
    const poll=async()=>{
      try {
        const state=await api.request<Snapshot>('/state',{cursor:cursor.current});
        if(!alive)return;
        if(deletedJobs.current.has(state.id)){timer=setTimeout(poll,900);return;}
        if(job.current!==state.id){job.current=state.id;setLogs([]);}
        cursor.current=state.cursor;setSnapshot(state);setConnection('connected');
        if(state.events?.length)setLogs(old=>[...old,...state.events].slice(-1500));
      } catch(e) {if(alive)setConnection('disconnected');}
      if(alive)timer=setTimeout(poll,900);
    };
    void poll();return()=>{alive=false;clearTimeout(timer);};
  },[]);
  useEffect(()=>{if(!toast)return;const t=setTimeout(()=>setToast(''),4000);return()=>clearTimeout(t);},[toast]);
  const open=async(root:string,settings?:string,repository?:string,version?:MavenVersionSettings)=>{
    const result=await api.request<OpenProject>('/project/open',{rootPomPath:root});
    if(settings!==undefined)result.config.maven.settingsPath=settings;
    if(repository!==undefined)result.config.maven.localRepository=repository;
    if(version)Object.assign(result.config.maven,version);
    result.config.maven.testPattern='';
    setData(result);setConfig(result.config);setHistorical(null);setSnapshot(idle);setLogs([]);
    setRecents(await api.request<RecentProject[]>('/projects'));setError('');
  };
  const finish=async()=>{
    if(!config)return;await api.request('/config/save',{config});setPage('dashboard');setHistorical(null);setToast('配置已保存');
  };
  const start=async(mode:string)=>{
    if(!config)return;setHistorical(null);setLogs([]);await api.request('/run/start',{config,mode});
    const state=await api.request<Snapshot>('/state',{cursor:0});cursor.current=state.cursor;setSnapshot(state);setLogs(state.events);setError('');
  };
  const showHistory=async()=>{
    if(!config)return;setJobs(await api.request<JobSummary[]>('/history',{rootPomPath:config.rootPomPath}));setPage('history');
  };
  const showJob=async(id:string)=>{
    if(!config)return;const result=await api.request<Snapshot&{config?:Config}>('/history/detail',{rootPomPath:config.rootPomPath,id});
    setHistorical(result);setPage(result.mode==='statistics'?'statistics':'dashboard');
  };
  const deleteHistory=async(id:string,deleteFiles:boolean)=>{
    if(!config)return;
    const result=await api.request<{id:string;warning:string;history:JobSummary[]}>('/history/delete',{rootPomPath:config.rootPomPath,id,deleteFiles});
    deletedJobs.current.add(id);setJobs(result.history);
    if(historical?.id===id)setHistorical(null);
    if(snapshot.id===id){setSnapshot(idle);setLogs([]);job.current='';}
    setToast(result.warning||'运行记录已删除');
  };
  const editConfig=(step:1|2=1)=>{if(busy)return;setWizardStep(step);setHistorical(null);setPage('wizard');};
  const newWorkspace=()=>{if(busy)return;setWizardStep(1);setData(null);setConfig(null);setHistorical(null);setWizardKey(n=>n+1);setPage('wizard');};
  return <div className="app-shell"><Titlebar notify={notify}/><aside className="sidebar"><div className="brand"><span className="brand-mark"><img src="./app-icon.png" alt="Coverage Loop 图标"/></span><strong>Coverage<span>Loop</span></strong><span className="version">1.3</span></div><button className="workspace-switch" disabled={busy} onClick={newWorkspace}><div className="workspace-avatar">{data?.project.rootArtifactId.slice(0,1).toUpperCase()||'W'}</div><span><strong>{data?.project.rootArtifactId||'本地工作区'}</strong><small>{data?'Maven 项目':'连接你的第一个项目'}</small></span><Plus size={16}/></button>
    <div className="nav-label">WORKSPACE</div><nav><button className={page==='dashboard'?'active':''} disabled={!config} onClick={()=>{setHistorical(null);setPage('dashboard');}}><LayoutDashboard size={17}/>工作看板</button><button className={page==='wizard'?'active':''} disabled={busy} onClick={()=>editConfig()}><FolderGit2 size={17}/>项目向导{config&&<span className="nav-count">{config.selectedModulePaths.length}</span>}</button><button className={page==='statistics'?'active':''} disabled={!config} onClick={()=>{setHistorical(null);setPage('statistics');}}><BarChart3 size={17}/>覆盖率统计</button><button className={page==='history'?'active':''} disabled={!config} onClick={()=>void showHistory().catch(e=>notify(String(e)))}><History size={17}/>运行记录</button></nav>
    <div className="sidebar-bottom"><div className="local-card"><Database size={17}/><div><strong>本地持久化</strong><p>项目与每轮记录自动保存</p></div><span className="dot"/></div><button className="help-link" onClick={()=>setToast('选择工程 → 定义范围 → 配置 Agent → 运行基线或自动补测。运行结果可在历史记录中回看。')}><CircleHelp size={15}/>使用说明<ArrowUpRight size={14}/></button><div className="sidebar-footer"><span className={'connection-dot '+connection}/>{connection==='connected'?'执行内核已连接':connection==='preview'?'浏览器界面预览':'执行内核连接中断'}<span>v{api.version}</span></div></div></aside>
    <main className="main"><header className="topbar"><div className="breadcrumb"><span>工作区</span><span>/</span><strong>{data?.project.rootArtifactId||'新建项目'}</strong>{data&&<><span>/</span><span>{page==='wizard'?'配置向导':page==='history'?'运行记录':page==='statistics'?'覆盖率统计':'工作看板'}</span></>}</div><div className="topbar-right"><span className="local-label"><span className="dot"/>LOCAL</span>{data&&<button className="icon-button" aria-label="编辑项目配置" disabled={busy} onClick={()=>editConfig()}><Settings2 size={17}/></button>}</div></header>
    {!isDesktop&&<div className="preview-notice">当前为界面预览；项目扫描与任务执行需要通过桌面应用启动。</div>}
    {error&&<div className="error-banner" role="alert"><span>{error}</span><button aria-label="关闭错误" onClick={()=>setError('')}><X size={16}/></button></div>}
    <motion.div className="page-content" key={page} initial={{opacity:0,y:7}} animate={{opacity:1,y:0}} transition={{duration:.22}}>{page==='wizard'&&<Wizard key={wizardKey} initialStep={wizardStep} data={data} config={config} setConfig={setConfig} onOpen={open} onFinish={finish} recents={recents} busy={busy} notify={notify}/>}
    {page==='dashboard'&&config&&<Dashboard config={(historical as (Snapshot&{config?:Config})|null)?.config??config} snapshot={historical??snapshot} logs={historical?[]:logs} onStart={start} onConfigureAgent={()=>editConfig(2)} onStop={async(stopMode='immediate')=>{await api.request('/run/stop',{mode:stopMode});setSnapshot(await api.request<Snapshot>('/state',{cursor:cursor.current}));}} busy={busy} notify={notify} historical={!!historical}/>}
    {page==='statistics'&&data&&config&&<Statistics key={config.rootPomPath} data={data} base={config} snapshot={snapshot} historical={historical} busy={busy} notify={notify} onStart={async c=>{setHistorical(null);await api.request('/run/start',{config:c,mode:'statistics'});setSnapshot(await api.request<Snapshot>('/state',{cursor:0}));}} onStop={async()=>{await api.request('/run/stop');}}/>}
    {page==='history'&&<section><div className="page-title"><div><div className="eyebrow">RUN HISTORY</div><h1>每一轮，都有记录。</h1><p>回看任务、覆盖率和测试结果。</p></div><button className="secondary" onClick={()=>void showHistory().catch(e=>notify(String(e)))}>刷新记录</button></div><HistoryList jobs={jobs} onOpen={showJob} onDelete={deleteHistory} notify={notify}/></section>}
    </motion.div></main><RuntimeDock snapshot={snapshot} logs={logs}/>{toast&&<div className="toast" role="status">{toast}<button onClick={()=>setToast('')} aria-label="关闭提示"><X size={15}/></button></div>}</div>;
}
