const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('coverage', {
  request: (route, payload = {}) => ipcRenderer.invoke('coverage:request', route, payload),
  choosePath: kind => ipcRenderer.invoke('coverage:choose', kind),
  recoverSession: selector => ipcRenderer.invoke('coverage:recover', selector),
  openArtifact: path => ipcRenderer.invoke('coverage:artifact', path),
  version: '1.2.2',
});
