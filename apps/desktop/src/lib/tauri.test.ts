import { expect, test, vi } from 'vitest';
import { createTauriBridge } from './tauri';

test('Tauri bridge preserves payloads and both stop modes without exposing backend credentials',async()=>{
  const invoke=vi.fn().mockResolvedValue({status:'running'});
  const bridge=createTauriBridge(invoke);
  await bridge.request('/run/stop',{mode:'after-round'});
  expect(invoke).toHaveBeenLastCalledWith('coverage_request',{route:'/run/stop',payload:{mode:'after-round'}});
  await bridge.request('/run/stop',{mode:'immediate'});
  expect(invoke).toHaveBeenLastCalledWith('coverage_request',{route:'/run/stop',payload:{mode:'immediate'}});
  await bridge.request('/state');
  expect(invoke).toHaveBeenLastCalledWith('coverage_request',{route:'/state',payload:{}});
  await bridge.choosePath('settings');
  expect(invoke).toHaveBeenLastCalledWith('choose_path',{kind:'settings'});
  const selector={rootPomPath:'D:/项目/pom.xml',id:'task',round:2};
  await bridge.recoverSession(selector);
  expect(invoke).toHaveBeenLastCalledWith('recover_session',{selector});
  await bridge.openArtifact('D:/项目/.coverage-loop/maven.log');
  expect(invoke).toHaveBeenLastCalledWith('open_artifact',{path:'D:/项目/.coverage-loop/maven.log'});
  invoke.mockRejectedValueOnce('正在恢复会话');
  await expect(bridge.request('/run/start')).rejects.toEqual('正在恢复会话');
});
