import { useState, useEffect, useRef } from 'react';
import { ChevronRight, Folder, FileCode2, Package, Search } from 'lucide-react';
import type { Config, Module, Sources, Scope } from '@/lib/types';

function Check({checked,mixed,onChange,label}:{checked:boolean;mixed:boolean;onChange:()=>void;label:string}) {
  const ref=useRef<HTMLInputElement>(null);
  useEffect(() => { if(ref.current) ref.current.indeterminate=mixed; },[mixed]);
  return <input ref={ref} type="checkbox" checked={checked} onChange={onChange} aria-label={label}/>;
}
export function ScopeTree({modules,sources,config,onChange}:{modules:Module[];sources:Sources[];config:Config;onChange:(config:Config)=>void}) {
  const [search,setSearch]=useState('');
  const [scopeFilter,setScopeFilter]=useState<'all'|'selected'>('all');
  const rule=(modulePath:string,kind:Scope['kind'],pattern:string)=>config.scopes.find(s=>s.modulePath===modulePath && s.kind===kind && s.pattern===pattern)?.mode ?? 'default';
  const setRule=(modulePath:string,kind:Scope['kind'],pattern:string,mode:string)=>{
    const scopes=config.scopes.filter(s=>!(s.modulePath===modulePath&&s.kind===kind&&s.pattern===pattern));
    if(mode!=='default') scopes.push({modulePath,kind,pattern,mode:mode as Scope['mode']});
    onChange({...config,scopes});
  };
  const Rule=({modulePath,kind,pattern}:{modulePath:string;kind:Scope['kind'];pattern:string})=><select className={'scope-select '+rule(modulePath,kind,pattern)} aria-label={pattern+' 范围规则'} value={rule(modulePath,kind,pattern)} onChange={e=>setRule(modulePath,kind,pattern,e.target.value)}><option value="default">默认</option><option value="include">Include</option><option value="exclude">Exclude</option></select>;
  const isDescendant=(parent:string,child:string)=>child!==parent && (parent==='.' || child.startsWith(parent+'/'));
  const childModules=(parent:Module|null)=>modules.filter(m=>{
    const ancestors=modules.filter(p=>isDescendant(p.relativePath,m.relativePath)).sort((a,b)=>b.relativePath.length-a.relativePath.length);
    return parent ? ancestors[0]?.relativePath===parent.relativePath : ancestors.length===0;
  });
  const renderModule=(module:Module)=>{
    const data=sources.find(s=>s.modulePath===module.relativePath);
    const ownClasses=data?.classes ?? [];
    const classes=ownClasses.filter(c=>c.qualifiedName.toLowerCase().includes(search.toLowerCase()));
    const descendants=modules.filter(m=>m.relativePath===module.relativePath||isDescendant(module.relativePath,m.relativePath));
    const selectable=descendants.filter(m=>m.hasMainSources||m.packaging!=='pom').map(m=>m.relativePath);
    const checked=selectable.length>0&&selectable.every(p=>config.selectedModulePaths.includes(p));
    const mixed=!checked&&selectable.some(p=>config.selectedModulePaths.includes(p));
    if(scopeFilter==='selected'&&!checked&&!mixed) return null;
    const packages=[...new Set(classes.map(c=>c.packageName))];
    return <details className="module-node" key={module.relativePath} open={search?true:undefined}>
      <summary><ChevronRight size={14}/><Check label={'选择模块 '+module.artifactId} checked={checked} mixed={mixed} onChange={()=>onChange({...config,selectedModulePaths:checked?config.selectedModulePaths.filter(p=>!selectable.includes(p)):[...new Set([...config.selectedModulePaths,...selectable])]})}/><Package size={16}/><span>{module.artifactId}</span><code>{module.relativePath}</code><span className="node-count">{data?.sourceFileCount ?? 0} 类 · {data?.testFileCount ?? 0} 测试文件</span></summary>
      {packages.map(pkg=><details className="package-node" key={pkg} open={search?true:undefined}><summary><ChevronRight size={13}/><Folder size={15}/><span>{pkg||'(默认包)'}</span><span className="node-count">{classes.filter(c=>c.packageName===pkg).length}</span><Rule modulePath={module.relativePath} kind="package" pattern={pkg}/></summary>
        {classes.filter(c=>c.packageName===pkg).map(c=><div className="class-node" key={c.qualifiedName}><FileCode2 size={14}/><span>{c.name}</span><Rule modulePath={module.relativePath} kind="class" pattern={c.qualifiedName}/></div>)}
      </details>)}
      {childModules(module).map(renderModule)}
      {!ownClasses.length&&!childModules(module).length&&<p className="tree-empty">此模块没有标准目录下的 Java 生产类</p>}
    </details>;
  };
  return <div className="tree-panel"><div className="tree-toolbar"><label className="search"><Search size={15}/><input placeholder="搜索包或类名…" value={search} onChange={e=>setSearch(e.target.value)}/></label><select aria-label="模块显示范围" value={scopeFilter} onChange={e=>setScopeFilter(e.target.value as 'all'|'selected')}><option value="all">所有模块</option><option value="selected">已选模块</option></select></div>
    <div className="tree-body">{childModules(null).map(renderModule)}</div>
    <div className="tree-footer"><span className="dot"/>已选 {config.selectedModulePaths.length} 个模块<span>{config.scopes.filter(s=>s.mode==='include').length} Include · {config.scopes.filter(s=>s.mode==='exclude').length} Exclude</span></div>
  </div>;
}
