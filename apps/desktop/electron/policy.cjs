const path = require('node:path');
const fs = require('node:fs');
const routes = new Set(['/projects','/project/open','/config/save','/config/load','/command/preview','/run/start','/run/stop','/state','/history','/history/detail']);
function validateRoute(route) { if (typeof route !== 'string' || !routes.has(route)) throw new Error('Unknown desktop operation'); return route; }
function isInside(root, target) { const relative = path.relative(root, target); return relative === '' || (!relative.startsWith('..' + path.sep) && relative !== '..' && !path.isAbsolute(relative)); }
function artifactPath(roots, input) {
  if (typeof input !== 'string') throw new Error('Invalid artifact path');
  const target = fs.realpathSync(input);
  if (!roots.some(root => { const evidence = path.join(root,'.coverage-loop'); return fs.existsSync(evidence) && isInside(fs.realpathSync(evidence),target); })) throw new Error('只能打开当前工程的 Coverage Loop 运行记录');
  return target;
}
module.exports = { validateRoute, isInside, artifactPath };
