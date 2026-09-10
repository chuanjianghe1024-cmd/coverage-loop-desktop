const { app, BrowserWindow, ipcMain, dialog, shell, nativeTheme } = require('electron');
const { spawn } = require('node:child_process');
const { randomBytes } = require('node:crypto');
const path = require('node:path');
const fs = require('node:fs');
const { validateRoute, artifactPath } = require('./policy.cjs');
const { terminalCommand } = require('./recovery.cjs');
let window, engine, base, token, recoveryTerminal, quitting = false;
const roots = new Set();
if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', () => { window?.show(); window?.focus(); });
  app.whenReady().then(boot).catch(error => { dialog.showErrorBox('Coverage Loop 启动失败', error.message); app.quit(); });
}
async function startEngine() {
  token = randomBytes(32).toString('hex');
  const root = path.resolve(__dirname, '../../..');
  const bundled = path.join(process.resourcesPath,'runtime','bin', process.platform === 'win32' ? 'java.exe' : 'java');
  const java = app.isPackaged ? bundled : process.env.COVERAGE_JAVA || (process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME,'bin',process.platform === 'win32' ? 'java.exe' : 'java') : 'java');
  const jar = app.isPackaged ? path.join(process.resourcesPath,'engine','coverage-loop-engine.jar') : path.join(root,'backend','target','coverage-loop-engine.jar');
  if (!fs.existsSync(jar)) throw new Error('找不到 Java 执行内核，请先运行 npm run build:backend。');
  engine = spawn(java, ['-jar', jar], { windowsHide: true, stdio: ['pipe','pipe','pipe'], env: { ...process.env, COVERAGE_SESSION_TOKEN: token, COVERAGE_PARENT_PIPE: '1', COVERAGE_DATA_DIR: app.getPath('userData') } });
  await new Promise((resolve,reject) => {
    let output = '', errors = '';
    const timeout = setTimeout(() => { engine.kill(); reject(new Error('Java 服务启动超时。')); },30000);
    engine.stderr.on('data', chunk => { errors = (errors + chunk.toString()).slice(-5000); });
    engine.stdout.on('data', chunk => {
      output = (output + chunk.toString()).slice(-5000);
      const match = output.match(/COVERAGE_READY (\d+)/);
      if (match) { base = 'http://127.0.0.1:' + match[1]; clearTimeout(timeout); resolve(); }
    });
    engine.once('error', e => { clearTimeout(timeout); reject(new Error('无法启动 Java：' + e.message)); });
    engine.once('exit', code => {
      clearTimeout(timeout);
      if (!base) reject(new Error('Java 服务退出：' + code + '\n' + errors));
      else if (!quitting) { dialog.showErrorBox('执行内核已退出','请重新启动应用。任务记录已保存在本机。\n' + errors); app.quit(); }
    });
  });
  await request('/health', {}, 'GET');
}
async function request(route,payload,method='POST') {
  const response = await fetch(base + route,{method,headers:{Authorization:'Bearer ' + token,'Content-Type':'application/json'},body:method === 'GET' ? undefined : JSON.stringify(payload),signal:AbortSignal.timeout(120000)});
  const data = await response.json(); if (!response.ok) throw new Error(data.error || '操作失败'); return data;
}
function checkSender(event) { if (!window || event.sender !== window.webContents || event.senderFrame !== window.webContents.mainFrame) throw new Error('Untrusted frame'); }
async function boot() {
  nativeTheme.themeSource="light";
  app.setAppUserModelId("com.coverageloop.desktop");
  await startEngine();
  ipcMain.handle('coverage:request',async (event,route,payload) => {
    checkSender(event); validateRoute(route);
    if (route === '/run/start' && recoveryTerminal) throw new Error('请先关闭正在恢复会话的终端，再启动新的任务');
    const result = await request(route,payload);
    if (route === '/project/open') roots.add(fs.realpathSync(result.project.rootDirectory));
    return result;
  });
  ipcMain.handle('coverage:choose',async (event,kind) => {
    checkSender(event);
    const directory = ['project','jdk','repository'].includes(kind);
    if (!['project','settings','jdk','maven','agent','repository'].includes(kind)) throw new Error('Invalid picker');
    const result = await dialog.showOpenDialog(window,{title: directory ? '选择目录' : '选择文件',properties:[directory ? 'openDirectory' : 'openFile'],...(['settings'].includes(kind) ? {filters:[{name:'Maven settings',extensions:['xml']}]} : {})});
    return result.canceled ? null : result.filePaths[0];
  });
  ipcMain.handle('coverage:recover',async (event,selector) => {
    checkSender(event);
    if (recoveryTerminal) throw new Error('恢复终端已经打开');
    recoveryTerminal = 'opening';
    try {
    const descriptor = await request('/round/recovery',selector);
    const command = terminalCommand(descriptor,[...roots]);
    const child = spawn(command.executable,command.args,{cwd:command.cwd,windowsHide:false,detached:true,stdio:'ignore'});
    recoveryTerminal = child;
    child.once('exit',()=>{if(recoveryTerminal===child)recoveryTerminal=null;});
    await new Promise((resolve,reject)=>{child.once('spawn',resolve);child.once('error',e=>{recoveryTerminal=null;reject(new Error('无法打开恢复终端：'+e.message));});});
    child.unref();
    return {sessionId:descriptor.sessionId};
    } catch(error) { recoveryTerminal = null; throw error; }
  });
  ipcMain.handle('coverage:artifact',async (event,input) => { checkSender(event); const error = await shell.openPath(artifactPath([...roots],input)); if (error) throw new Error(error); });
  window = new BrowserWindow({width:1440,height:960,minWidth:1080,minHeight:760,backgroundColor:'#f4f9f4',title:'Coverage Loop',icon:path.join(__dirname,'../build/icon.ico'),titleBarStyle:'hidden',...(process.platform!=='darwin'?{titleBarOverlay:{color:'#e6f2e8',symbolColor:'#234d36',height:36}}:{}),autoHideMenuBar:true,webPreferences:{preload:path.join(__dirname,'preload.cjs'),contextIsolation:true,nodeIntegration:false,sandbox:true}});
  window.webContents.setWindowOpenHandler(() => ({action:'deny'}));
  window.webContents.on('will-navigate',event => event.preventDefault());
  window.on('close',async event => {
    if (quitting) return;
    event.preventDefault();
    try {
      const state = await request('/state',{cursor:Number.MAX_SAFE_INTEGER});
      if (['running','stopping'].includes(state.status)) {
        const answer = await dialog.showMessageBox(window,{type:'question',buttons:['继续运行','停止并退出'],defaultId:0,cancelId:0,message:'任务仍在运行，是否停止后退出？'});
        if (answer.response !== 1) return;
      }
    } catch {}
    quitting = true; app.quit();
  });
  const url = process.env.COVERAGE_DEV_URL;
  if (!app.isPackaged && url === 'http://127.0.0.1:5173') await window.loadURL(url);
  else await window.loadFile(path.join(__dirname,'../dist/index.html'));
}
app.on('before-quit', () => { quitting = true; engine?.stdin.end(); });
app.on('window-all-closed', () => app.quit());
