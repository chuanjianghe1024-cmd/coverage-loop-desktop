use crate::policy;
use base64::Engine;
use serde::Deserialize;
use std::{
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
};

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Descriptor {
    pub available: bool,
    pub cwd: PathBuf,
    pub executable: String,
    pub args: Vec<String>,
    pub session_id: String,
}

pub fn literal(value: &str) -> Result<String, String> {
    if value.contains(['\0', '\r', '\n']) {
        return Err("无效的恢复参数".into());
    }
    Ok(format!("'{}'", value.replace('\'', "''")))
}

pub fn powershell_script(executable: &str, args: &[String], cwd: &Path) -> Result<String, String> {
    let program = literal(executable)?;
    let args = args
        .iter()
        .map(|a| literal(a))
        .collect::<Result<Vec<_>, _>>()?
        .join(", ");
    let invoke = if !executable.contains(['/', '\\']) && Path::new(executable).extension().is_none()
    {
        format!("$coverageResumeExe = (Get-Command -Name {program} -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source\n& $coverageResumeExe @coverageResumeArgs")
    } else {
        format!("& {program} @coverageResumeArgs")
    };
    Ok(format!("$ErrorActionPreference = 'Stop'\nSet-Location -LiteralPath {}\n$coverageResumeArgs = @({args})\n{invoke}", literal(&cwd.to_string_lossy())?))
}

pub fn encoded_script(script: &str) -> String {
    let bytes: Vec<u8> = script.encode_utf16().flat_map(u16::to_le_bytes).collect();
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

pub fn launch(descriptor: &Descriptor, roots: &[PathBuf]) -> Result<Child, String> {
    if !descriptor.available || descriptor.executable.is_empty() {
        return Err("没有可恢复的会话".into());
    }
    let cwd = policy::project_root(roots, &descriptor.cwd)?;
    // Validate even on non-Windows platforms so all launchers have the same contract.
    literal(&descriptor.executable)?;
    for arg in &descriptor.args {
        literal(arg)?;
    }
    let mut command = if cfg!(windows) {
        let executable = std::env::var_os("SystemRoot")
            .map(PathBuf::from)
            .unwrap_or_else(|| "C:\\Windows".into())
            .join("System32/WindowsPowerShell/v1.0/powershell.exe");
        let mut command = Command::new(executable);
        command
            .args(["-NoLogo", "-NoProfile", "-NoExit", "-EncodedCommand"])
            .arg(encoded_script(&powershell_script(
                &descriptor.executable,
                &descriptor.args,
                &cwd,
            )?));
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x00000010); // CREATE_NEW_CONSOLE; deliberately not hidden
        }
        command
    } else if cfg!(target_os = "linux") {
        let mut command = Command::new("x-terminal-emulator");
        command
            .arg("-e")
            .arg(&descriptor.executable)
            .args(&descriptor.args);
        command
    } else {
        return Err("当前系统暂不支持打开恢复终端".into());
    };
    command
        .current_dir(cwd)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("无法打开恢复终端：{e}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn windows_recovery_keeps_paths_and_arguments_literal() {
        let args = vec![
            "chat".into(),
            "--resume".into(),
            "中文 ' $() ; & ` safe".into(),
        ];
        let script = powershell_script(
            "C:\\Tools & ' agent\\hermes.cmd",
            &args,
            Path::new("C:\\项目 空格"),
        )
        .unwrap();
        assert!(script.contains("& 'C:\\Tools & '' agent\\hermes.cmd' @coverageResumeArgs"));
        assert!(script.contains("'中文 '' $() ; & ` safe'"));
        let bytes = base64::engine::general_purpose::STANDARD
            .decode(encoded_script(&script))
            .unwrap();
        let decoded = String::from_utf16(
            &bytes
                .chunks_exact(2)
                .map(|b| u16::from_le_bytes([b[0], b[1]]))
                .collect::<Vec<_>>(),
        )
        .unwrap();
        assert_eq!(decoded, script);
        assert!(literal("bad\nargument").is_err());
        assert!(powershell_script("hermes", &args, Path::new("C:\\project"))
            .unwrap()
            .contains("Get-Command"));
    }

    #[cfg(windows)]
    #[test]
    fn real_powershell_receives_exact_unicode_arguments() {
        let temp = tempfile::tempdir().unwrap();
        let file = temp.path().join("fake agent.ps1");
        let output = temp.path().join("arguments.json");
        std::fs::write(
            &file,
            format!(
                "ConvertTo-Json -InputObject @($args) | Set-Content -Encoding UTF8 -LiteralPath {}",
                literal(&output.to_string_lossy()).unwrap()
            ),
        )
        .unwrap();
        let expected = vec![
            "chat".into(),
            "--resume".into(),
            "中文 ' $() ; & ` safe".into(),
        ];
        let script = powershell_script(&file.to_string_lossy(), &expected, temp.path()).unwrap();
        let result = Command::new("powershell.exe")
            .args(["-NoProfile", "-EncodedCommand", &encoded_script(&script)])
            .output()
            .unwrap();
        assert!(
            result.status.success(),
            "{}",
            String::from_utf8_lossy(&result.stderr)
        );
        let data = std::fs::read_to_string(output).unwrap();
        assert_eq!(
            serde_json::from_str::<Vec<String>>(data.trim_start_matches('\u{feff}')).unwrap(),
            expected
        );
    }
}
