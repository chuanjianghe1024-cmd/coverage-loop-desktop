import { invoke, isTauri } from '@tauri-apps/api/core';
import type { Bridge } from './types';
import tauriConfig from '../../src-tauri/tauri.conf.json';

export const isTauriDesktop = isTauri();
type Invoke = typeof invoke;

export function createTauriBridge(call: Invoke): Bridge {
  return {
    version: tauriConfig.version,
    request: (route, payload = {}) => call('coverage_request', { route, payload }),
    choosePath: kind => call('choose_path', { kind }),
    openArtifact: path => call('open_artifact', { path }),
    recoverSession: selector => call('recover_session', { selector }),
  };
}

export const tauriBridge = createTauriBridge(invoke);
