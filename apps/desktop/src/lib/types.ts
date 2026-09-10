export interface Scope { modulePath: string; kind: 'package' | 'class'; mode: 'include' | 'exclude'; pattern: string }
export interface Config {
  id: string; name: string; rootPomPath: string; selectedModulePaths: string[]; scopes: Scope[];
  maven: { useBundledMaven: boolean; preInstall: boolean; parallelThreads: number; executable: string; javaHome: string; settingsPath: string; localRepository: string; versionNumber: string; forceUpdate: boolean; profiles: string[]; extraArgs: string[]; testPattern: string };
  coverage: { jacocoVersion: string; lineThreshold: number; branchThreshold: number };
  agent: { enabled: boolean; provider: string; executable: string; model: string; hermesProvider: string; opencodeAgent: string; opencodeAttach: string; extraArgs: string[]; batchSize: number; maxRounds: number; maxSameFailures: number; timeoutMinutes: number; heartbeatSeconds: number; autoApprove: boolean; allowProductionChanges: boolean; coveragePromptTemplate: string; repairPromptTemplate: string };
}
export interface Module { name: string; artifactId: string; relativePath: string; packaging: string; hasMainSources: boolean; hasTests: boolean }
export interface JavaClass { name: string; packageName: string; qualifiedName: string; relativePath: string }
export interface Sources { modulePath: string; packages: string[]; classes: JavaClass[]; testClasses: JavaClass[]; sourceFileCount: number; testFileCount: number }
export interface Project { rootPomPath: string; rootDirectory: string; rootArtifactId: string; modules: Module[]; warnings: string[] }
export interface OpenProject { project: Project; sources: Sources[]; config: Config; configs: Config[] }
export interface RecentProject { rootPomPath: string; name: string; openedAt: string }
export interface ClassResult { modulePath: string; className: string; qualifiedName: string; packageName: string; coveredLines: number; missedLines: number; lineCoverage?: number; currentLineCoverage?: number; baselineLineCoverage?: number }
export interface Round {
  runId: string; round: number; exitCode: number | null; runDirectory: string; logPath: string; coverageSnapshotPath: string;
  startedAt: string; finishedAt: string;
  tests: { tests: number; failures: number; errors: number; skipped: number; status: string; message: string; duration?: number };
  coverage: { modulePath: string; source: string; classCount: number; coveredLines: number; missedLines: number; classes: ClassResult[] }[];
  moduleProgress?:ModuleProgress[]; noTestModules?:string[];
  groups: { initialSatisfied: ClassResult[]; pending: ClassResult[]; supplemented: ClassResult[] };
}
export interface ModuleProgress {key:string;modulePath:string;name:string;phase:string;stage:string;status:string;startedAt?:string;finishedAt?:string;dependency:boolean}
export interface Progress { round: number; stage: string; percent: number; message: string; indeterminate?:boolean; modules?:ModuleProgress[] }
export interface RoundSelector {rootPomPath:string;id:string;round:number}
export interface RecordFile {name:string;path:string;exists:boolean;size:number}
export interface Recovery {available:boolean;reason?:string;sessionId?:string;originRound?:number;provider?:string;executable?:string;args?:string[];cwd?:string}
export interface RoundRecordsData {files:RecordFile[];directory:string;recovery:Recovery}
export interface LogEvent { id: number; type: string; data: { text?: string; stream?: string; timestamp?: string } }
export interface Snapshot {
  id: string; status: string; mode: string; message: string; configId?: string; configName?: string; statistics?: StatisticsNode; startedAt?: string; finishedAt?: string; progress?: Progress;
  agentRounds?: { round: number; changedTestFiles: string[]; selectedClassCount: number; completionMarkerSeen: boolean }[];
  latest?: Round; rounds: Round[]; cursor: number; events: LogEvent[]; truncated?: boolean;
  loop?: { agentRounds: { round: number; changedTestFiles: string[]; selectedClassCount: number; completionMarkerSeen: boolean }[] };
}
export interface JobSummary { id: string; status: string; mode: string; message: string; startedAt: string; finishedAt?: string; name: string }
export type PathKind = 'project' | 'settings' | 'jdk' | 'maven' | 'agent' | 'repository';
export interface Bridge { request: <T>(route: string, payload?: unknown) => Promise<T>; choosePath: (kind: PathKind) => Promise<string | null>; openArtifact: (path: string) => Promise<void>; recoverSession:(selector:RoundSelector)=>Promise<{sessionId:string}>; version: string }
declare global { interface Window { coverage?: Bridge } }

export interface StatisticsNode { id:string; name:string; kind:string; coveredLines:number; totalLines:number; classCount:number; lineCoverage:number|null; measured:boolean; children:StatisticsNode[] }
export interface TestSelection {modulePath:string;sourceCount:number;packages:string[];tests:string[]}
