import { useEffect, useState } from 'react';
import { Clock3, RefreshCw } from 'lucide-react';
import type { Progress } from '@/lib/types';

export function RetryCountdown({retryAt}:{retryAt:string}) {
  const [now,setNow]=useState(Date.now());
  useEffect(()=>{setNow(Date.now());const timer=setInterval(()=>setNow(Date.now()),1000);return()=>clearInterval(timer);},[retryAt]);
  const seconds=Math.max(0,Math.ceil((Date.parse(retryAt)-now)/1000));
  return <span>{seconds?`${Math.floor(seconds/60)} 分 ${seconds%60} 秒后重试`:'即将重试'}</span>;
}

export function RetryStatus({progress,stopAfterRound}:{progress?:Progress;stopAfterRound?:boolean}) {
  if(stopAfterRound)return <div className="retry-status" role="status"><Clock3 size={18}/><div><strong>已请求当前轮结束后停止</strong><p>当前 Agent 操作结束后会验证已写入的测试，再停止任务。等待重试期间会直接停止；需要中断当前进程时请点击“立即停止”。</p></div></div>;
  if(progress?.stage!=='retry-waiting'||!progress.retryAt)return null;
  return <div className="retry-status" role="status"><RefreshCw size={18} className="spin"/><div><strong>等待自动重试 · <RetryCountdown retryAt={progress.retryAt}/></strong><p>{progress.message}</p><small>已保留本轮记录和测试改动 · 第 {progress.retryAttempt??1} 次恢复等待</small></div></div>;
}
