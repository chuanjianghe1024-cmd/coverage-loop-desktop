import { useState } from 'react';
import { render,screen,waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { test,expect,vi } from 'vitest';
import { HistoryList } from './HistoryList';
import type { JobSummary } from '@/lib/types';
const entries:JobSummary[]=[{id:'finished',name:'已完成任务',mode:'loop',status:'completed',message:'完成',startedAt:'2026-09-10T00:00:00Z'},{id:'active',name:'正在执行',mode:'statistics',status:'running',message:'测试中',startedAt:'2026-09-10T01:00:00Z'}];
test('history deletion is separate from opening, requires confirmation and can retain files',async()=>{
 const user=userEvent.setup(),open=vi.fn(),remove=vi.fn().mockResolvedValue(undefined);
 function Harness(){const[jobs,setJobs]=useState(entries);return <HistoryList jobs={jobs} onOpen={open} onDelete={async(id,files)=>{await remove(id,files);setJobs(items=>items.filter(j=>j.id!==id));}} notify={vi.fn()}/>;}
 render(<Harness/>);expect(screen.getByRole('button',{name:'删除运行记录 正在执行'})).toBeDisabled();
 await user.click(screen.getByRole('button',{name:'删除运行记录 已完成任务'}));expect(open).not.toHaveBeenCalled();expect(remove).not.toHaveBeenCalled();
 expect(screen.getByRole('checkbox',{name:'同时清理本次日志与报告文件'})).not.toBeChecked();
 await user.click(screen.getByRole('button',{name:'取消'}));expect(remove).not.toHaveBeenCalled();
 await user.click(screen.getByRole('button',{name:'删除运行记录 已完成任务'}));await user.click(screen.getByRole('button',{name:'确认删除'}));
 expect(remove).toHaveBeenCalledWith('finished',false);await waitFor(()=>expect(screen.queryByRole('button',{name:'删除运行记录 已完成任务'})).not.toBeInTheDocument());expect(screen.getByText('正在执行 · 覆盖率统计')).toBeInTheDocument();
});
test('file cleanup is explicit and deletion errors keep the record available',async()=>{
 const user=userEvent.setup(),remove=vi.fn().mockRejectedValue(new Error('文件正在被占用')),notify=vi.fn();
 render(<HistoryList jobs={entries} onOpen={vi.fn()} onDelete={remove} notify={notify}/>);
 await user.click(screen.getByRole('button',{name:'删除运行记录 已完成任务'}));await user.click(screen.getByRole('checkbox',{name:'同时清理本次日志与报告文件'}));await user.click(screen.getByRole('button',{name:'确认删除'}));
 expect(remove).toHaveBeenCalledWith('finished',true);expect(notify).toHaveBeenCalledWith('Error: 文件正在被占用');expect(screen.getByRole('button',{name:'删除运行记录 已完成任务'})).toBeInTheDocument();
});
