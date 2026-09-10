import { RefreshCw, GitBranch } from 'lucide-react';
export interface MavenVersionSettings {versionNumber:string;forceUpdate:boolean}
export function MavenVersionFields({value,onChange}:{value:MavenVersionSettings;onChange:(value:MavenVersionSettings)=>void}) {
  return <div className="maven-version-fields"><label className="field"><span><GitBranch size={13}/>Maven 版本号（version_number）</span><input aria-label="Maven 版本号（version_number）" value={value.versionNumber??''} onChange={e=>onChange({...value,versionNumber:e.target.value})} placeholder="例如 1.0.0；留空使用项目配置" spellCheck={false}/><small className="field-hint">自动传入 -Dversion_number，预构建和每轮测试共用此值。</small></label><label className="toggle-row dependency-update"><span><strong><RefreshCw size={13}/>强制更新依赖（-U）</strong><small>重新检查此前下载失败的依赖；不会自动修复依赖 POM 中的版本占位符。</small></span><input type="checkbox" checked={value.forceUpdate??false} onChange={e=>onChange({...value,forceUpdate:e.target.checked})}/></label></div>;
}
