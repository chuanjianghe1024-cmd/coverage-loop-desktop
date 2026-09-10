import { useState, useEffect, useRef, type ReactNode } from 'react';
import { AnimatePresence, motion } from 'motion/react';
import { ChevronRight, Folder, FileCode2, Package, Search } from 'lucide-react';
import { packageTree, packageMembers, type PackageNode } from '@/lib/package-tree';
import type { Config, Module, Sources, Scope } from '@/lib/types';

function Check({checked,mixed=false,onChange,label}:{checked:boolean;mixed?:boolean;onChange:()=>void;label:string}) {
  const ref=useRef<HTMLInputElement>(null);
  useEffect(()=>{if(ref.current)ref.current.indeterminate=mixed;},[mixed]);
  return <input ref={ref} type="checkbox" checked={checked} onChange={onChange} aria-label={label}/>;
}
export function TreeBranch({label,children,initialOpen=false,name}:{label:ReactNode;children:ReactNode;initialOpen?:boolean;name:string}) {
  const [open,setOpen]=useState(initialOpen);
  return <div className="tree-branch"><div className="tree-row"><button type="button" className="tree-chevron" aria-label={(open?'收起 ':'展开 ')+name} aria-expanded={open} onClick={()=>setOpen(!open)}><motion.span animate={{rotate:open?90:0}} transition={{duration:.16}}><ChevronRight size={15}/></motion.span></button>{label}</div><AnimatePresence initial={false}>{open&&<motion.div className="tree-children" initial={{height:0,opacity:0}} animate={{height:'auto',opacity:1}} exit={{height:0,opacity:0}} transition={{duration:.18}}>{children}</motion.div>}</AnimatePresence></div>;
}
function matches(scope:Scope,name:string) {
  return scope.kind==='package'?(scope.pattern?name.startsWith(scope.pattern+'.'):!name.includes('.')):name===scope.pattern||name.startsWith(scope.pattern+'$');
}
export function classSelected(config:Config,modulePath:string,name:string) {
  if(!config.selectedModulePaths.includes(modulePath))return false;
  const scopes=config.scopes.filter(s=>s.modulePath===modulePath),includes=scopes.filter(s=>s.mode==='include');
  return (!includes.length||includes.some(s=>matches(s,name)))&&!scopes.some(s=>s.mode==='exclude'&&matches(s,name));
}
export function selectedClassCount(config:Config,sources:Sources[]) {
  return sources.reduce((n,s)=>n+s.classes.filter(c=>classSelected(config,s.modulePath,c.qualifiedName)).length,0);
}
export function ScopeTree({modules,sources,config,onChange}:{modules:Module[];sources:Sources[];config:Config;onChange:(config:Config)=>void}) {
  const [search,setSearch]=useState('');
  const [filter,setFilter]=useState('all');
  const isDescendant=(parent:string,child:string)=>child!==parent&&(parent==='.'||child.startsWith(parent+'/'));
  const childModules=(parent:Module|null)=>modules.filter(m=>{
    const ancestors=modules.filter(p=>isDescendant(p.relativePath,m.relativePath)).sort((a,b)=>b.relativePath.length-a.relativePath.length);
    return parent?ancestors[0]?.relativePath===parent.relativePath:!ancestors.length;
  });
  const setClasses=(modulePath:string,names:Set<string>)=>{
    const all=sources.find(s=>s.modulePath===modulePath)?.classes??[];
    const scopes=config.scopes.filter(s=>s.modulePath!==modulePath);
    if(names.size&&names.size!==all.length) for(const pattern of names)scopes.push({modulePath,kind:'class',mode:'include',pattern});
    onChange({...config,scopes,selectedModulePaths:[...config.selectedModulePaths.filter(p=>p!==modulePath),...(names.size?[modulePath]:[])]});
  };
  const renderModule=(module:Module):ReactNode=>{
    const data=sources.find(s=>s.modulePath===module.relativePath),all=data?.classes??[];
    const descendants=modules.filter(m=>m.relativePath===module.relativePath||isDescendant(module.relativePath,m.relativePath));
    const selectable=descendants.filter(m=>m.hasMainSources).map(m=>m.relativePath);
    const total=sources.filter(s=>selectable.includes(s.modulePath)).flatMap(s=>s.classes.map(c=>({m:s.modulePath,c})));
    const count=total.filter(x=>classSelected(config,x.m,x.c.qualifiedName)).length;
    const checked=total.length>0&&count===total.length;
    if(filter==='selected'&&!count)return null;
    const selected=new Set(all.filter(c=>classSelected(config,module.relativePath,c.qualifiedName)).map(c=>c.qualifiedName));
    const toggle=(names:string[],enable:boolean)=>{const next=new Set(selected);for(const n of names)enable?next.add(n):next.delete(n);setClasses(module.relativePath,next);};
    const renderPackage=(node:PackageNode):ReactNode=>{
      const members=packageMembers(node),visible=members.some(c=>c.qualifiedName.toLowerCase().includes(search.toLowerCase()));
      if(!visible)return null;
      const n=members.filter(c=>selected.has(c.qualifiedName)).length;
      return <TreeBranch key={node.path+':'+Boolean(search)} name={node.path||'默认包'} initialOpen={Boolean(search)} label={<><Check label={'选择包 '+(node.path||'默认包')} checked={n===members.length} mixed={n>0&&n<members.length} onChange={()=>toggle(members.map(c=>c.qualifiedName),n!==members.length)}/><Folder size={15}/><span className="tree-name" title={node.path}>{node.name}</span><span className="node-count">{n} / {members.length}</span></>}>
        {node.classes.filter(c=>c.qualifiedName.toLowerCase().includes(search.toLowerCase())).map(c=><div className={'tree-row tree-leaf '+(selected.has(c.qualifiedName)?'selected':'')} key={c.qualifiedName}><Check label={'选择类 '+c.qualifiedName} checked={selected.has(c.qualifiedName)} onChange={()=>toggle([c.qualifiedName],!selected.has(c.qualifiedName))}/><FileCode2 size={14}/><span className="tree-name">{c.name}</span><span className="selection-label">{selected.has(c.qualifiedName)?'纳入':'排除'}</span></div>)}
        {node.children.map(renderPackage)}
      </TreeBranch>;
    };
    return <TreeBranch key={module.relativePath+':'+Boolean(search)} name={module.artifactId} initialOpen={true} label={<><Check label={'选择模块 '+module.artifactId} checked={checked} mixed={count>0&&!checked} onChange={()=>onChange({...config,selectedModulePaths:[...config.selectedModulePaths.filter(p=>!selectable.includes(p)),...(!checked?selectable:[])],scopes:config.scopes.filter(s=>!selectable.includes(s.modulePath))})}/><Package size={16}/><span className="tree-name">{module.artifactId}</span><code>{module.relativePath}</code><span className="node-count">{count} / {total.length} 类</span></>}>
      {packageTree(all).map(renderPackage)}{childModules(module).map(renderModule)}{!all.length&&!childModules(module).length&&<p className="tree-empty">此模块没有可选择的 Java 生产类</p>}
    </TreeBranch>;
  };
  return <div className="tree-panel"><div className="tree-toolbar"><label className="search"><Search size={15}/><input placeholder="搜索包或类名…" value={search} onChange={e=>setSearch(e.target.value)}/></label><select aria-label="模块显示范围" value={filter} onChange={e=>setFilter(e.target.value)}><option value="all">所有模块</option><option value="selected">已选模块</option></select></div><div className="tree-body">{childModules(null).map(renderModule)}</div><div className="tree-footer"><span className="dot"/>已选 {config.selectedModulePaths.length} 个模块<span>{selectedClassCount(config,sources)} 个生产类 · 勾选纳入，取消排除</span></div></div>;
}
