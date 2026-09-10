import type { Round } from './types';
export const buildInvalid=(round?:Round)=>!!round&&(['build-failed','aborted'].includes(round.tests.status)||(round.exitCode!==0&&round.tests.status!=='test-failed'));
export function metrics(round?: Round) {
  const coverage=round?.coverage ?? [];
  const covered=coverage.reduce((n,m)=>n+m.coveredLines,0), missed=coverage.reduce((n,m)=>n+m.missedLines,0);
  const invalid=buildInvalid(round),verified=!invalid&&coverage.length>0&&coverage.every(m=>m.source==='jacoco')&&covered+missed>0;
  return {coverage:verified?covered/(covered+missed)*100:null,tests:round?.tests.tests ?? 0,failedTests:(round?.tests.failures??0)+(round?.tests.errors??0),pending:round?.groups.pending.length??0,classes:coverage.reduce((n,m)=>n+m.classCount,0),covered,missed,verified,invalid};
}
export const percent=(n:number|null|undefined)=>n==null?'—':n.toFixed(1)+'%';
export const delta=(n:number)=>n>0?'+'+n:String(n);
