const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');
const {validateRoute,artifactPath,isInside} = require('./policy.cjs');
test('renderer cannot turn IPC into an arbitrary URL request', () => {
  assert.equal(validateRoute('/run/start'),'/run/start');
  for (const route of ['https://example.com','/../health','/run/start?x=1','/shutdown']) assert.throws(() => validateRoute(route));
});
test('artifact opening rejects sibling paths and paths outside evidence', () => {
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'coverage-policy-'));
  try {
    const evidence=path.join(root,'.coverage-loop');fs.mkdirSync(evidence);
    const log=path.join(evidence,'run.log');fs.writeFileSync(log,'ok');
    const source=path.join(root,'pom.xml');fs.writeFileSync(source,'source');
    assert.equal(artifactPath([root],log),fs.realpathSync(log));
    assert.throws(() => artifactPath([root],source));
    assert.equal(isInside(evidence,evidence+'-other/log'),false);
  } finally {fs.rmSync(root,{recursive:true,force:true});}
});
