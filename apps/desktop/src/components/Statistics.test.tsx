import {render,screen,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {beforeEach,test,expect,vi} from 'vitest';
import {config,project} from '../test/fixtures';
import type {Config,Snapshot,StatisticsNode} from '../lib/types';
const bridge=vi.hoisted(()=>({request:vi.fn(),choosePath:vi.fn(),openArtifact:vi.fn()}));
vi.mock('../lib/api',()=>({api:bridge}));
import {Statistics,StatisticsResult} from './Statistics';
const idle:Snapshot={id:'',status:'idle',mode:'',message:'',rounds:[],events:[],cursor:0};
let stored:Config[]=[];
beforeEach(()=>{stored=[];bridge.request.mockReset();bridge.request.mockImplementation(async(route:string,payload:{config:Config;id:string})=>{
 if(route==='/statistics/configs')return structuredClone(stored);
 if(route==='/statistics/preview')return [{modulePath:'module-a',sourceCount:1,packages:['com.sample.a'],tests:['com.sample.a.GreeterTest']}];
 if(route==='/statistics/save'){stored=[...stored.filter(c=>c.id!==payload.config.id),structuredClone(payload.config)];return {configs:structuredClone(stored),path:'/sample/.coverage-loop/statistics/configs/'+payload.config.id+'.json'};}
 if(route==='/statistics/delete'){stored=stored.filter(c=>c.id!==payload.id);return structuredClone(stored);}
 throw Error(route);
});});
test('select classes visually, save independent configurations, reload and execute exact selection',async()=>{
 const user=userEvent.setup(),start=vi.fn().mockResolvedValue(undefined);
 render(<Statistics data={project} base={config} snapshot={idle} historical={null} onStart={start} onStop={vi.fn()} busy={false} notify={vi.fn()}/>);
 await waitFor(()=>expect(screen.getByRole('button',{name:'新建'})).toBeEnabled());
 await user.click(screen.getByRole('checkbox',{name:'选择模块 module-a'}));
 await user.click(screen.getByRole('button',{name:'展开 com.sample.a'}));
 await user.click(screen.getByRole('checkbox',{name:'选择类 com.sample.a.Other'}));
 await user.type(screen.getByLabelText('Maven 版本号（version_number）'),'2.0.0');
 await user.click(screen.getByRole('checkbox',{name:/强制更新依赖/}));
 const name=screen.getByLabelText('统计配置名称');await user.clear(name);await user.type(name,'服务 A');
 await user.click(screen.getByRole('button',{name:'保存配置'}));
 expect(await screen.findByText('统计配置已保存')).toBeInTheDocument();
 await user.click(screen.getByRole('button',{name:'另存为'}));
 expect(await screen.findByText('已另存为独立配置')).toBeInTheDocument();expect(stored).toHaveLength(2);
 await user.selectOptions(screen.getByLabelText('已保存的统计配置'),stored[0].id);
 expect(screen.getByLabelText('统计配置名称')).toHaveValue('服务 A');
 expect(screen.getByLabelText('Maven 版本号（version_number）')).toHaveValue('2.0.0');expect(stored[0].maven.forceUpdate).toBe(true);
 await user.click(screen.getByRole('button',{name:'运行覆盖率统计'}));
 expect(start).toHaveBeenCalledWith(expect.objectContaining({id:stored[0].id,selectedModulePaths:['module-a'],scopes:[{modulePath:'module-a',kind:'class',mode:'include',pattern:'com.sample.a.Greeter'}]}));
 expect(screen.queryByLabelText('测试匹配规则')).not.toBeInTheDocument();
});
test('result tree shows exact counters and distinguishes unavailable reports',async()=>{
 const leaf:StatisticsNode={id:'c',name:'Example',kind:'class',coveredLines:3,totalLines:10,lineCoverage:30,measured:true,classCount:1,children:[]};
 const root:StatisticsNode={...leaf,id:'p',name:'sample',kind:'project',children:[leaf]};
 const {rerender}=render(<StatisticsResult node={root}/>);
 expect(screen.getAllByText('3 行 / 10 行')).toHaveLength(2);expect(screen.getAllByText('30.00%')).toHaveLength(2);
 rerender(<StatisticsResult node={{...root,measured:false,lineCoverage:null,children:[]}}/>);
 expect(screen.getByText('未测')).toBeInTheDocument();expect(screen.queryByText('0.00%')).not.toBeInTheDocument();
});
