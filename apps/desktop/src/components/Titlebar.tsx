import { Minus, Square, X } from 'lucide-react';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { isTauriDesktop } from '@/lib/tauri';

export function Titlebar({notify}:{notify:(message:string)=>void}) {
  const control = (action:'minimize'|'toggleMaximize'|'close') => {
    void getCurrentWindow()[action]().catch(error=>notify(String(error)));
  };
  return <div className={'desktop-titlebar'+(isTauriDesktop?' tauri-titlebar':'')} data-tauri-drag-region>
    <div data-tauri-drag-region><img src="./app-icon.png" alt="" data-tauri-drag-region/><strong data-tauri-drag-region>Coverage Loop</strong><span data-tauri-drag-region>让每一行，都有迹可循。</span></div>
    {isTauriDesktop&&<div className="window-controls">
      <button aria-label="最小化窗口" title="最小化" onClick={()=>control('minimize')}><Minus size={15}/></button>
      <button aria-label="最大化或还原窗口" title="最大化 / 还原" onClick={()=>control('toggleMaximize')}><Square size={12}/></button>
      <button className="window-close" aria-label="关闭窗口" title="关闭" onClick={()=>control('close')}><X size={17}/></button>
    </div>}
  </div>;
}
