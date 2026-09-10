import { AlertTriangle } from 'lucide-react';
import type { Round } from '@/lib/types';
import { buildInvalid } from '@/lib/metrics';
export function RoundFailure({round}:{round?:Round}) {
  if(!round||!buildInvalid(round))return null;
  const title=round.tests.failureKind==='dependency-resolution'?'依赖解析失败，本轮覆盖率无效':round.tests.status==='aborted'?'任务已中止，本轮覆盖率无效':'构建未完成，本轮覆盖率无效';
  return <div className="round-failure" role="alert"><AlertTriangle size={20}/><div><strong>{title}</strong><p>{round.tests.message}</p><small>{round.tests.tests===0?'本轮未取得有效测试结果':'本轮已执行 '+round.tests.tests+' 个测试，已完成模块的报告仅供排查'}，所选类暂不评估是否达标。请在下方“打开本轮记录”中查看 maven.log。</small></div></div>;
}
