import { render,screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect,test,vi } from 'vitest';
const control=vi.hoisted(()=>({minimize:vi.fn().mockResolvedValue(undefined),toggleMaximize:vi.fn().mockResolvedValue(undefined),close:vi.fn().mockResolvedValue(undefined)}));
vi.mock('@tauri-apps/api/window',()=>({getCurrentWindow:()=>control}));
vi.mock('@/lib/tauri',()=>({isTauriDesktop:true}));
import { Titlebar } from './Titlebar';

test('window controls call native operations and report failures without bypassing close confirmation',async()=>{
  const notify=vi.fn(),user=userEvent.setup();render(<Titlebar notify={notify}/>);
  await user.click(screen.getByRole('button',{name:'最小化窗口'}));expect(control.minimize).toHaveBeenCalledOnce();
  await user.click(screen.getByRole('button',{name:'最大化或还原窗口'}));expect(control.toggleMaximize).toHaveBeenCalledOnce();
  await user.click(screen.getByRole('button',{name:'关闭窗口'}));expect(control.close).toHaveBeenCalledOnce();
  control.close.mockRejectedValueOnce(new Error('窗口忙碌'));
  await user.click(screen.getByRole('button',{name:'关闭窗口'}));expect(notify).toHaveBeenCalledWith('Error: 窗口忙碌');
});
