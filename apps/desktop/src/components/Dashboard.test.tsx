import { render,screen,within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect,test,vi } from 'vitest';
import { Dashboard } from './Dashboard';
import {config,snapshot,round} from '../test/fixtures';
import {metrics} from '../lib/metrics';
test('clicking a historical round changes the missing-class detail and DT baseline',async()=>{
 const user=userEvent.setup();render(<Dashboard config={config} snapshot={snapshot} logs={[]} onStart={vi.fn()} onConfigureAgent={vi.fn()} onStop={vi.fn()} busy={false} notify={vi.fn()} historical={false}/>);
 expect(screen.getByText('当前范围内没有未达标类')).toBeInTheDocument();
 await user.click(screen.getByRole('button',{name:/R01 基线/}));
 expect(screen.getByText('Greeter',{selector:'strong'})).toBeInTheDocument();
 const tables=screen.getAllByRole('table');
 const row=within(tables[0]).getAllByRole('row')[2];
 expect(within(row).getByText('+2')).toBeInTheDocument();
 expect(within(row).getByText('+50 pp')).toBeInTheDocument();
});
test('coverage is line-weighted and source fallback is not a measured percentage',()=>{
 const r=round(1,50,3);r.coverage.push({modulePath:'b',source:'jacoco',classCount:1,coveredLines:0,missedLines:900,classes:[]});
 expect(metrics(r).coverage).toBe(5);
 r.coverage[0].source='source-fallback';expect(metrics(r).coverage).toBeNull();
});
test('dependency failure shows an invalid round instead of a coverage deficit or a zero percent result',()=>{
 const failed=round(3,0,0);failed.exitCode=1;failed.tests.status='build-failed';failed.tests.failureKind='dependency-resolution';failed.tests.message='请检查父 POM 版本';
 render(<Dashboard config={config} snapshot={{...snapshot,status:'failed',rounds:[failed],latest:failed}} logs={[]} onStart={vi.fn()} onConfigureAgent={vi.fn()} onStop={vi.fn()} busy={false} notify={vi.fn()} historical/>);
 expect(within(screen.getByRole('alert')).getByText('依赖解析失败，本轮覆盖率无效')).toBeInTheDocument();
 expect(screen.getByRole('button',{name:/待评估类/})).toBeInTheDocument();expect(screen.getByText('未评估')).toBeInTheDocument();
 expect(metrics(failed).coverage).toBeNull();expect(screen.queryByText('0.0%')).not.toBeInTheDocument();
});
test('retry waiting offers both stop modes and graceful stop still allows immediate interruption',async()=>{
 const user=userEvent.setup(),onStop=vi.fn().mockResolvedValue(undefined);
 const running={...snapshot,status:'running',mode:'loop',progress:{round:2,stage:'retry-waiting',percent:0,message:'API 超时，等待服务恢复',retryAt:new Date(Date.now()+240000).toISOString(),retryAttempt:3}};
 const props={config,snapshot:running,logs:[],onStart:vi.fn(),onConfigureAgent:vi.fn(),onStop,busy:true,notify:vi.fn(),historical:false};
 const view=render(<Dashboard {...props}/>);
 expect(screen.getByText(/秒后重试/)).toBeInTheDocument();
 await user.click(screen.getByRole('button',{name:'当前轮结束后停止'}));
 expect(onStop).toHaveBeenCalledWith('after-round');
 view.rerender(<Dashboard {...props} snapshot={{...running,stopAfterRoundRequested:true}}/>);
 expect(screen.getByRole('button',{name:'已请求本轮后停止'})).toBeDisabled();
 expect(screen.getByText('已请求当前轮结束后停止')).toBeInTheDocument();
 expect(screen.getByRole('button',{name:'立即停止'})).toBeEnabled();
 await user.click(screen.getByRole('button',{name:'立即停止'}));
 expect(onStop).toHaveBeenLastCalledWith('immediate');
 expect(screen.queryByRole('button',{name:'开始补测'})).not.toBeInTheDocument();
});
