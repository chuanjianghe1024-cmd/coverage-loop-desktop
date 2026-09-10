import type { Bridge } from './types';
export const isDesktop = Boolean(window.coverage);
export const api: Bridge = window.coverage ?? {
  version: '1.2.2',
  request: async () => { throw new Error('这是浏览器界面预览。请通过 npm run dev 启动桌面应用后访问本地项目。'); },
  choosePath: async () => { throw new Error('目录选择需要在桌面应用中使用。'); },
  recoverSession: async () => { throw new Error('会话恢复需要通过桌面应用打开终端。'); },
  openArtifact: async () => { throw new Error('请在桌面应用中打开运行记录。'); },
};
