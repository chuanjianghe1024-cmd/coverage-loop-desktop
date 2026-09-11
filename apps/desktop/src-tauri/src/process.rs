use std::process::{Child, Command};
use std::time::{Duration, Instant};

pub fn hide_console(command: &mut Command) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000); // CREATE_NO_WINDOW
    }
    #[cfg(not(windows))]
    let _ = command;
}

/// Java owns its Maven/Agent children. EOF asks it to stop them and close SQLite.
/// On Windows the job is also a fallback when the shell is killed unexpectedly.
pub struct ManagedProcess {
    pub child: Child,
    #[cfg(windows)]
    job: usize,
}

impl ManagedProcess {
    pub fn new(mut child: Child) -> Result<Self, String> {
        #[cfg(windows)]
        {
            use std::os::windows::io::AsRawHandle;
            use windows_sys::Win32::{Foundation::CloseHandle, System::JobObjects::*};
            unsafe {
                let job = CreateJobObjectW(std::ptr::null(), std::ptr::null());
                let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
                info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
                if job.is_null()
                    || SetInformationJobObject(
                        job,
                        JobObjectExtendedLimitInformation,
                        &info as *const _ as *const _,
                        std::mem::size_of_val(&info) as u32,
                    ) == 0
                    || AssignProcessToJobObject(job, child.as_raw_handle() as _) == 0
                {
                    let error = std::io::Error::last_os_error();
                    if !job.is_null() {
                        CloseHandle(job);
                    }
                    let _ = child.kill();
                    let _ = child.wait();
                    return Err(format!("无法管理执行内核进程：{error}"));
                }
                Ok(Self {
                    child,
                    job: job as usize,
                })
            }
        }
        #[cfg(not(windows))]
        {
            let _ = &mut child;
            Ok(Self { child })
        }
    }

    pub fn shutdown(&mut self) {
        self.child.stdin.take();
        let deadline = Instant::now() + Duration::from_secs(8);
        while Instant::now() < deadline {
            if matches!(self.child.try_wait(), Ok(Some(_))) {
                return;
            }
            std::thread::sleep(Duration::from_millis(50));
        }
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

impl Drop for ManagedProcess {
    fn drop(&mut self) {
        self.shutdown();
        #[cfg(windows)]
        unsafe {
            windows_sys::Win32::Foundation::CloseHandle(self.job as _);
        }
    }
}
