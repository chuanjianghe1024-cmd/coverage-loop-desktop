# UI 组件来源与获取

界面使用 Aceternity 官方 Bento Grid 和 Hover Border Gradient。它们由 `npm ci` 的 postinstall（或 `npm run ui:sync`）从官方 registry 下载到 `apps/desktop/src/components/ui/`，这些文件不纳入 Git。仓库只包含接入代码、哈希锁定和少量适配操作。

- Bento Grid：https://ui.aceternity.com/registry/bento-grid.json
- Hover Border Gradient：https://ui.aceternity.com/registry/hover-border-gradient.json
- 原作者：Manu Arora / Aceternity
- 许可：https://ui.aceternity.com/licence

官方下载内容与 `scripts/aceternity-lock.json` 的 SHA-256 一致时才写入文件。上游更新后需要审查新源码并更新哈希。Bento Grid 通过工作台 CSS 调整布局；Hover Border Gradient 清理未用 import，增加减少动效支持、effect 依赖和 disabled 类型，渐变边缘改为浅绿色。应用侧增加树展开、数字、进度条与页面切换动效，统一标题栏和浅色表面。

构建后的应用是终端产品；不将组件源码或模板作为可再分发素材发布。开发人员应遵循 Aceternity 许可。自动下载器不读取密钥、不发送 Authorization。仅手动使用 shadcn CLI 时可通过 components.json 的 `ACETERNITY_API_KEY` 引用安装需要鉴权的组件；密钥不进入应用运行时或前端环境变量。
