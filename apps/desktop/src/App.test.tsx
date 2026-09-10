import { render,screen,waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach,expect,test,vi } from 'vitest';
import { project,config } from './test/fixtures';
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
 await user.click(screen.getByRole('button',{name:'扫描工程并继续'}));
 expect(await screen.findByText('定义本次补测范围')).toBeInTheDocument();
 expect(screen.getByRole('checkbox',{name:'选择模块 module-a'})).toBeChecked();
 expect(screen.queryByLabelText('测试匹配规则')).not.toBeInTheDocument();
 await user.click(screen.getByRole('button',{name:'展开 com.sample.a'}));
 expect(screen.getByText('Greeter',{selector:'span'})).toBeInTheDocument();
 await user.click(screen.getByRole('checkbox',{name:'选择类 com.sample.a.Other'}));
 await user.click(screen.getByRole('button',{name:'配置补测策略'}));
 await user.click(screen.getByRole('checkbox',{name:/启用自动补测/}));
 const batch=screen.getByLabelText('每批处理类数');await user.clear(batch);await user.type(batch,'5');
 await user.clear(screen.getByLabelText('覆盖率补充提示词'));await user.type(screen.getByLabelText('覆盖率补充提示词'),'优先验证边界条件');
 await user.click(screen.getByRole('button',{name:'保存并进入看板'}));
 await waitFor(()=>expect(screen.getByText('让每一轮补测，都有迹可循。')).toBeInTheDocument());
 const saved=bridge.request.mock.calls.find(c=>c[0]==='/config/save')?.[1].config as typeof config;
 expect(saved.maven.settingsPath).toBe('/company/settings.xml');
 expect(saved.agent.batchSize).toBe(5);expect(saved.agent.coveragePromptTemplate).toBe('优先验证边界条件');
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
