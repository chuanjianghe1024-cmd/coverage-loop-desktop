import { useState } from 'react';
import { render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';
import { ScopeTree } from './ScopeTree';
import { RuntimeDock } from './RuntimeDock';
import { RoundRecords } from './RoundRecords';
import { Dashboard } from './Dashboard';
import { api } from '@/lib/api';
import { config, project, round, snapshot } from '../test/fixtures';
import type { RoundRecordsData, Snapshot } from '@/lib/types';
afterEach(()=>vi.restoreAllMocks());
test('packages contain own classes and subpackages; parent checkbox selects the entire subtree',async()=>{
  const user=userEvent.setup();const sources=[{...project.sources[0],classes:['api.A','api.B','api.conf.C','api.dep.D'].map(q=>({name:q.split('.').at(-1)!,qualifiedName:q,packageName:q.slice(0,q.lastIndexOf('.')),relativePath:q.replaceAll('.','/')+'.java'}))}];
  function Harness(){const [c,set]=useState({...config,selectedModulePaths:[] as string[]});return <ScopeTree config={c} modules={project.project.modules} sources={sources} onChange={set}/>;}
  render(<Harness/>);expect(screen.queryByRole('checkbox',{name:'选择包 api.conf'})).not.toBeInTheDocument();
  await user.click(screen.getByRole('button',{name:'展开 api'}));
  const branch=screen.getByRole('checkbox',{name:'选择包 api'}).closest('.tree-branch')! as HTMLElement;
  expect(within(branch).getByRole('checkbox',{name:'选择类 api.A'})).toBeInTheDocument();expect(within(branch).getByRole('checkbox',{name:'选择包 api.conf'})).toBeInTheDocument();
  await user.click(screen.getByRole('checkbox',{name:'选择包 api'}));expect(screen.getByRole('checkbox',{name:'选择包 api.dep'})).toBeChecked();
  await user.click(screen.getByRole('button',{name:'展开 api.conf'}));expect(screen.getByRole('checkbox',{name:'选择类 api.conf.C'})).toBeChecked();
  await user.click(screen.getByRole('checkbox',{name:'选择类 api.A'}));expect(screen.getByRole('checkbox',{name:'选择包 api'})).toBePartiallyChecked();
});
test('collapsed console retains current module and can show lifecycle progress',async()=>{
  const user=userEvent.setup();const state:Snapshot={...snapshot,status:'running',finishedAt:undefined,progress:{round:2,stage:'compiling',percent:0,message:'正在执行',modules:[{key:'coverage:a',modulePath:'module-a',name:'module-a',phase:'coverage',stage:'compile',status:'running',dependency:false,startedAt:new Date().toISOString()}]}};
  render(<RuntimeDock snapshot={state} logs={[{id:1,type:'log',data:{text:'[INFO] compiling sources\n'}}]}/>);
  const toggle=screen.getByRole('button',{name:/运行控制台/});expect(toggle).toHaveAttribute('aria-expanded','false');expect(screen.getByText('module-a · 编译')).toBeInTheDocument();
  await user.click(toggle);expect(screen.getByRole('log')).toHaveTextContent('compiling sources');await user.click(screen.getByRole('tab',{name:/模块进度/}));
  expect(screen.getByText('编译')).toBeInTheDocument();expect(screen.getByText('0 / 1 个模块已结束')).toBeInTheDocument();await user.click(toggle);expect(toggle).toHaveAttribute('aria-expanded','false');
});
test('record tabs follow selected round, discard late responses, and recover only when idle',async()=>{
  const user=userEvent.setup();const names=['coverage.json','coverage-gate.txt','failed-classes.txt','maven.log','summary.txt','session.json'];
  const records:RoundRecordsData={directory:'/evidence',files:names.map(name=>({name,path:'/evidence/'+name,exists:true,size:10})),recovery:{available:true,sessionId:'ses_A',originRound:1,provider:'opencode'}};
  let late:(value:unknown)=>void=()=>{};
  vi.spyOn(api,'request').mockImplementation(async(route,payload)=>{const p=payload as {round:number;name?:string};if(route==='/round/records')return records;return p.round===1&&p.name==='coverage.json'?new Promise(resolve=>{late=resolve;}):{content:`R${p.round}: ${p.name}`,truncated:false};});
  const recover=vi.spyOn(api,'recoverSession').mockResolvedValue({sessionId:'ses_A'});const props={rootPomPath:config.rootPomPath,jobId:'job',notify:vi.fn()};
  const view=render(<RoundRecords {...props} round={round(1,40,3)} busy/>);await screen.findByRole('tab',{name:'session.json'});expect(screen.getByRole('button',{name:/Recover session/})).toBeDisabled();
  view.rerender(<RoundRecords {...props} round={round(2,90,5)} busy={false}/>);await screen.findByText('R2: coverage.json');late({content:'WRONG ROUND',truncated:false});
  await user.click(screen.getByRole('tab',{name:'summary.txt'}));expect(await screen.findByText('R2: summary.txt')).toBeInTheDocument();expect(screen.queryByText('WRONG ROUND')).not.toBeInTheDocument();
  await user.click(screen.getByRole('button',{name:/Recover session/}));expect(recover).toHaveBeenCalledWith({rootPomPath:config.rootPomPath,id:'job',round:2});
});
test('missing classes collapse independently from round records',async()=>{
  const user=userEvent.setup();vi.spyOn(api,'request').mockImplementation(async route=>route==='/round/records'?{files:[],directory:'/evidence',recovery:{available:false,reason:'没有会话'}}:{content:'本轮记录',truncated:false});
  render(<Dashboard config={config} snapshot={{...snapshot,rounds:[round(1,40,3)]}} logs={[]} onStart={vi.fn()} onConfigureAgent={vi.fn()} onStop={vi.fn()} busy={false} notify={vi.fn()} historical/>);
  expect(screen.getByText('Greeter',{selector:'strong'})).toBeInTheDocument();await user.click(screen.getByRole('button',{name:/未达标类 · R01/}));
  await waitFor(()=>expect(screen.queryByText('Greeter',{selector:'strong'})).not.toBeInTheDocument());expect(screen.getByRole('region',{name:'打开本轮记录'})).toBeInTheDocument();
});
