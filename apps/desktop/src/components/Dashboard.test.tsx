import { render,screen,within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect,test,vi } from 'vitest';
import { Dashboard } from './Dashboard';
import {config,snapshot,round} from '../test/fixtures';
import {metrics} from '../lib/metrics';
test('clicking a historical round changes the missing-class detail and DT baseline',async()=>{
 const user=userEvent.setup();render(<Dashboard config={config} snapshot={snapshot} logs={[]} onStart={vi.fn()} onStop={vi.fn()} busy={false} notify={vi.fn()} historical={false}/>);
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
