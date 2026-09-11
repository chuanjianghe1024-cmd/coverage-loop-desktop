import type { Bridge } from './types';
import { isTauriDesktop, tauriBridge } from './tauri';
export const isDesktop = Boolean(window.coverage) || isTauriDesktop;
export const api: Bridge = window.coverage ?? (isTauriDesktop ? tauriBridge : {
  version: '1.3.0',
  request: async () => { throw new Error('这是浏览器界面预览。请通过 npm run dev 启动桌面应用后访问本地项目。'); },
  choosePath: async () => { throw new Error('目录选择需要在桌面应用中使用。'); },
  recoverSession: async () => { throw new Error('会话恢复需要通过桌面应用打开终端。'); },
  openArtifact: async () => { throw new Error('请在桌面应用中打开运行记录。'); },
});
