const fs = require('node:fs');
const path = require('node:path');
function literal(value) {
  if (typeof value !== 'string' || /[\0\r\n]/.test(value)) throw new Error('无效的恢复参数');
  return "'" + value.replaceAll("'", "''") + "'";
}
function terminalCommand(descriptor, roots, platform = process.platform) {
  if (!descriptor.available || !Array.isArray(descriptor.args)) throw new Error('没有可恢复的会话');
  const cwd = fs.realpathSync(descriptor.cwd);
  if (!roots.some(root => fs.realpathSync(root) === cwd)) throw new Error('请先打开会话对应的工程');
  const executable = literal(descriptor.executable), args = descriptor.args.map(literal);
  if (platform === 'win32') {
    const script = "$ErrorActionPreference = 'Stop'\nSet-Location -LiteralPath " + literal(cwd) +
      '\n$coverageResumeArgs = @(' + args.join(', ') + ')' +
      (!/[\\/]/.test(descriptor.executable) && !path.extname(descriptor.executable)
        ? '\n$coverageResumeExe = (Get-Command -Name ' + executable + ' -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source\n& $coverageResumeExe @coverageResumeArgs'
        : '\n& ' + executable + ' @coverageResumeArgs');
    return { executable: path.win32.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe'),
      args: ['-NoLogo', '-NoProfile', '-NoExit', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')], cwd };
  }
  if (platform === 'linux') return { executable: 'x-terminal-emulator', args: ['-e', descriptor.executable, ...descriptor.args], cwd };
  throw new Error('当前系统暂不支持打开恢复终端；Windows 版可直接恢复。');
}
module.exports = { literal, terminalCommand };
