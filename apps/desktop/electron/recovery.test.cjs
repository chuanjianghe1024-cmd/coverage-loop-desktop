const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { terminalCommand, literal } = require('./recovery.cjs');
test('recovery uses encoded PowerShell with literal paths and native session arguments', () => {
  const root=fs.mkdtempSync(path.join(os.tmpdir(),"coverage space & ' quote-"));
  try {
    const descriptor={available:true,cwd:root,executable:"C:\\Tools & ' agent\\hermes.cmd",args:['chat','--resume','20260910_120000_abc123']};
    const command=terminalCommand(descriptor,[root],'win32');
    const script=Buffer.from(command.args.at(-1),'base64').toString('utf16le');
    assert.ok(script.includes('Set-Location -LiteralPath '+literal(fs.realpathSync(root))));
    assert.ok(script.includes("& 'C:\\Tools & '' agent\\hermes.cmd' @coverageResumeArgs"));
    assert.ok(!script.includes('--continue'));assert.equal(command.cwd,fs.realpathSync(root));
    assert.throws(()=>terminalCommand(descriptor,[],'win32'));assert.throws(()=>literal('a\nb'));
    assert.equal(literal('$(whoami); `echo hi`'),"'$(whoami); `echo hi`'");
  } finally {fs.rmSync(root,{recursive:true,force:true});}
});
test('Windows terminal receives exact argument values in a real PowerShell process',{skip:process.platform!=='win32'},()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),"coverage & quote' -"));
  try{
    const output=path.join(root,'args.json'),fake=path.join(root,'fake-agent.ps1');
    fs.writeFileSync(fake,'$args | ConvertTo-Json | Set-Content -LiteralPath '+literal(output));
    const expected=['chat','--resume','20260910_120000_abc123',"quotes ' $() ; & ` safe"];
    const command=terminalCommand({available:true,cwd:root,executable:fake,args:expected},[root],'win32');
    const result=spawnSync(command.executable,command.args.filter(a=>a!=='-NoExit'),{encoding:'utf8'});
    assert.equal(result.status,0,result.stderr);assert.deepEqual(JSON.parse(fs.readFileSync(output,'utf8').replace(/^\uFEFF/,'')),expected);
  }finally{fs.rmSync(root,{recursive:true,force:true});}
});
