import { render,screen,waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach,expect,test,vi } from 'vitest';
import { project,config,round } from './test/fixtures';
import type { Snapshot } from './lib/types';
const bridge=vi.hoisted(()=>({request:vi.fn(),choosePath:vi.fn(),openArtifact:vi.fn(),version:'test'}));
vi.mock('./lib/api',()=>({api:bridge,isDesktop:true}));
import App from './App';
beforeEach(()=>{
 bridge.request.mockReset();
 bridge.request.mockImplementation(async(route:string)=>{
  if(route==='/projects')return [];
  if(route==='/project/open')return structuredClone(project);
  if(route==='/config/save')return {configs:[]};
  if(route==='/state')return {id:'',status:'idle',mode:'',message:'等待运行',rounds:[],events:[],cursor:0};
  throw new Error('Unexpected route '+route);
 });
});
test('root + settings -> module/class rules -> editable prompts and batch -> saved dashboard',async()=>{
 const user=userEvent.setup();render(<App/>);
 await user.type(screen.getByLabelText('项目根目录',{exact:true}),'/sample');
 await user.type(screen.getByLabelText('Maven settings.xml（可选）',{exact:true}),'/company/settings.xml');
 await user.type(screen.getByLabelText('Maven 版本号（version_number）'),'1.0.0');
 await user.click(screen.getByRole('checkbox',{name:/强制更新依赖/}));
 await user.click(screen.getByRole('button',{name:'扫描工程并继续'}));
 expect(await screen.findByText('定义本次补测范围')).toBeInTheDocument();
 expect(screen.getByRole('checkbox',{name:'选择模块 module-a'})).toBeChecked();
 expect(screen.queryByLabelText('测试匹配规则')).not.toBeInTheDocument();
 await user.click(screen.getByRole('button',{name:'展开 com.sample.a'}));
 expect(screen.getByText('Greeter',{selector:'span'})).toBeInTheDocument();
 await user.click(screen.getByRole('checkbox',{name:'选择类 com.sample.a.Other'}));
 await user.click(screen.getByRole('button',{name:'配置补测策略'}));
 await user.click(screen.getByRole('checkbox',{name:/启用自动补测/}));
 expect(screen.getByRole('checkbox',{name:/持续运行直到达标/})).toBeChecked();
 expect(screen.queryByLabelText('最多验证轮数')).not.toBeInTheDocument();
 await user.clear(screen.getByLabelText('初始重试等待（秒）'));await user.type(screen.getByLabelText('初始重试等待（秒）'),'90');
 await user.clear(screen.getByLabelText('最长重试等待（秒）'));await user.type(screen.getByLabelText('最长重试等待（秒）'),'900');
 const batch=screen.getByLabelText('每批处理类数');await user.clear(batch);await user.type(batch,'5');
 await user.clear(screen.getByLabelText('覆盖率补充提示词'));await user.type(screen.getByLabelText('覆盖率补充提示词'),'优先验证边界条件');
 await user.click(screen.getByRole('button',{name:'保存并进入看板'}));
 await waitFor(()=>expect(screen.getByText('让每一轮补测，都有迹可循。')).toBeInTheDocument());
 const saved=bridge.request.mock.calls.find(c=>c[0]==='/config/save')?.[1].config as typeof config;
 expect(saved.maven.settingsPath).toBe('/company/settings.xml');
 expect(saved.maven.versionNumber).toBe('1.0.0');expect(saved.maven.forceUpdate).toBe(true);
 expect(saved.agent.batchSize).toBe(5);expect(saved.agent.coveragePromptTemplate).toBe('优先验证边界条件');
 expect(saved.agent.retryDelaySeconds).toBe(90);expect(saved.agent.maxRetryDelaySeconds).toBe(900);
 expect(saved.maven.localRepository).toBe('D:/m2');
 expect(saved.scopes).toEqual([{modulePath:'module-a',kind:'class',pattern:'com.sample.a.Greeter',mode:'include'}]);
 expect(screen.getByRole('button',{name:'开始补测'})).toBeEnabled();
});
test('scan errors remain visible without advancing',async()=>{
 bridge.request.mockImplementation(async(route:string)=>{if(route==='/project/open')throw new Error('找不到 pom.xml');if(route==='/projects')return [];return {id:'',status:'idle',rounds:[],events:[],cursor:0};});
 const user=userEvent.setup();render(<App/>);await user.type(screen.getByLabelText('项目根目录',{exact:true}),'/missing');await user.click(screen.getByRole('button',{name:'扫描工程并继续'}));
 expect(await screen.findByRole('alert')).toHaveTextContent('找不到 pom.xml');
 expect(screen.getByRole('button',{name:'扫描工程并继续'})).toBeEnabled();
});
test('a completed baseline guides a disabled Agent directly to strategy settings without losing the scope or results',async()=>{
 const opened=structuredClone(project);
 opened.config.agent.model='configured-model';
 let state:Snapshot={id:'',status:'idle',mode:'',message:'等待运行',rounds:[],events:[],cursor:0};
 bridge.request.mockImplementation(async(route:string,payload?:{mode?:string})=>{
  if(route==='/projects')return [];
  if(route==='/project/open')return opened;
  if(route==='/config/save')return {configs:[]};
  if(route==='/state')return state;
  if(route==='/round/records')return {files:[],directory:'/evidence',recovery:{available:false,reason:'仅运行基线'}};
  if(route==='/run/start'){
   state=payload?.mode==='baseline'
    ?{...state,id:'baseline-job',mode:'baseline',status:'completed',message:'基线已完成',rounds:[round(1,40,3)],latest:round(1,40,3)}
    :{...state,id:'loop-job',mode:'loop',status:'running',message:'正在启动补测',rounds:[],latest:undefined};
   return {id:state.id};
  }
  throw new Error('Unexpected route '+route);
 });
 const user=userEvent.setup();render(<App/>);
 await user.type(screen.getByLabelText('项目根目录',{exact:true}),'/sample');
 await user.click(screen.getByRole('button',{name:'扫描工程并继续'}));
 await user.click(await screen.findByRole('button',{name:'展开 com.sample.a'}));
 await user.click(screen.getByRole('checkbox',{name:'选择类 com.sample.a.Other'}));
 await user.click(screen.getByRole('button',{name:'配置补测策略'}));
 expect(screen.getByRole('checkbox',{name:/启用自动补测/})).not.toBeChecked();
 await user.click(screen.getByRole('button',{name:'保存并进入看板'}));
 await user.click(await screen.findByRole('button',{name:'运行基线'}));
 expect(await screen.findByText('基线已完成',{selector:'strong'})).toBeInTheDocument();
 expect(screen.getByText(/自动补测尚未启用/)).toBeVisible();
 await user.click(screen.getByRole('button',{name:'配置 Agent 后补测'}));
 expect(await screen.findByRole('heading',{name:'Agent 助手'})).toBeInTheDocument();
 expect(screen.queryByText('定义本次补测范围')).not.toBeInTheDocument();
 expect(screen.getByLabelText('模型（可选）')).toHaveValue('configured-model');
 expect(screen.getByRole('checkbox',{name:/启用自动补测/})).not.toBeChecked();
 expect(bridge.request.mock.calls.filter(c=>c[0]==='/run/start')).toHaveLength(1);
 await user.click(screen.getByRole('checkbox',{name:/启用自动补测/}));
 await user.click(screen.getByRole('button',{name:'保存并进入看板'}));
 const start=await screen.findByRole('button',{name:'开始补测'});
 expect(start).toBeEnabled();expect(screen.getByText('基线已完成',{selector:'strong'})).toBeInTheDocument();
 expect(screen.queryByText(/自动补测尚未启用/)).not.toBeInTheDocument();
 await user.click(start);
 const calls=bridge.request.mock.calls.filter(c=>c[0]==='/run/start');
 expect(calls.map(c=>c[1].mode)).toEqual(['baseline','loop']);
 expect(calls[0][1].config.agent.enabled).toBe(false);
 expect(calls[1][1].config.agent.enabled).toBe(true);
 expect(calls[1][1].config.agent.model).toBe('configured-model');
 expect(calls[1][1].config.scopes).toEqual([{modulePath:'module-a',kind:'class',pattern:'com.sample.a.Greeter',mode:'include'}]);
 expect(calls[1][1].config.maven).toEqual(calls[0][1].config.maven);
});
