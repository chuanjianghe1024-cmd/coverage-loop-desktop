use crate::process::{hide_console, ManagedProcess};
use serde_json::{json, Value};
use std::{
    io::{BufRead, BufReader, Read},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Arc, Mutex,
    },
    time::Duration,
};

pub struct EnginePaths {
    pub java: PathBuf,
    pub jar: PathBuf,
    pub data: PathBuf,
    pub legacy: Option<PathBuf>,
}

impl EnginePaths {
    pub fn resolve(resources: &Path, config_dir: &Path, data: PathBuf) -> Self {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
        let java_name = if cfg!(windows) { "java.exe" } else { "java" };
        let (java, jar) = if cfg!(debug_assertions) {
            let java = std::env::var_os("COVERAGE_JAVA")
                .map(PathBuf::from)
                .or_else(|| {
                    std::env::var_os("JAVA_HOME")
                        .map(|p| PathBuf::from(p).join("bin").join(java_name))
                })
                .unwrap_or_else(|| PathBuf::from(java_name));
            (java, root.join("backend/target/coverage-loop-engine.jar"))
        } else {
            (
                resources.join("runtime/bin").join(java_name),
                resources.join("engine/coverage-loop-engine.jar"),
            )
        };
        Self {
            java,
            jar,
            legacy: legacy_workspace(config_dir, &data),
            data,
        }
    }
}

/// Electron has shipped with either its product name or npm package name as userData.
/// Import once into the trial's independent database; never open the old database for writes.
pub fn legacy_workspace(config_dir: &Path, data: &Path) -> Option<PathBuf> {
    if data.join("workspace.db").exists() {
        return None;
    }
    [
        "Coverage Loop",
        "@coverage-loop/desktop",
        "coverage-loop-desktop",
    ]
    .iter()
    .map(|name| config_dir.join(name).join("workspace.db"))
    .filter(|p| p.is_file())
    .max_by_key(|p| p.metadata().and_then(|m| m.modified()).ok())
}

pub struct Engine {
    process: Mutex<ManagedProcess>,
    client: reqwest::Client,
    base: String,
    token: String,
    pub closing: AtomicBool,
    pub ui_ready: AtomicBool,
    pub roots: Mutex<Vec<PathBuf>>,
    pub operations: tokio::sync::Mutex<()>,
    pub recovery: Mutex<Option<std::process::Child>>,
}

fn error_tail(output: &Arc<Mutex<String>>) -> String {
    output.lock().map(|s| s.clone()).unwrap_or_default()
}

impl Engine {
    pub fn start(paths: EnginePaths) -> Result<Self, String> {
        if !paths.jar.is_file() {
            return Err("找不到 Java 执行内核，请先构建后端或重新安装应用".into());
        }
        std::fs::create_dir_all(&paths.data).map_err(|e| e.to_string())?;
        let jar = dunce::canonicalize(&paths.jar).map_err(|e| e.to_string())?;
        let data = dunce::canonicalize(&paths.data).map_err(|e| e.to_string())?;
        // Keep PATH lookup for a bare executable name. Resolve relative executable
        // paths before changing the child's working directory.
        let java = if paths.java.is_absolute() || paths.java.components().count() > 1 {
            dunce::canonicalize(&paths.java).map_err(|e| e.to_string())?
        } else {
            paths.java
        };
        let token = format!(
            "{}{}",
            uuid::Uuid::new_v4().simple(),
            uuid::Uuid::new_v4().simple()
        );
        // Loopback requests must not pass through a machine's HTTP proxy.
        let client = reqwest::Client::builder()
            .no_proxy()
            .timeout(Duration::from_secs(120))
            .build()
            .map_err(|e| e.to_string())?;
        let mut command = Command::new(java);
        command
            // Windows resource_dir has a verbatim \\?\ prefix, which Java's JAR
            // launcher cannot open. Pass a relative JAR name from its directory.
            .current_dir(jar.parent().ok_or("内核目录无效")?)
            .arg("-jar")
            .arg(jar.file_name().ok_or("内核文件名无效")?)
            .env("COVERAGE_SESSION_TOKEN", &token)
            .env("COVERAGE_PARENT_PIPE", "1")
            .env("COVERAGE_DATA_DIR", data)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        if let Some(legacy) = paths.legacy {
            command.env(
                "COVERAGE_IMPORT_DB",
                dunce::canonicalize(legacy).map_err(|e| e.to_string())?,
            );
        } else {
            command.env_remove("COVERAGE_IMPORT_DB");
        }
        hide_console(&mut command);
        let child = command
            .spawn()
            .map_err(|e| format!("无法启动 Java 执行内核：{e}"))?;
        let mut managed = ManagedProcess::new(child)?;
        let stdout = managed.child.stdout.take().ok_or("缺少内核输出管道")?;
        let mut stderr = managed.child.stderr.take().ok_or("缺少内核错误管道")?;
        let errors = Arc::new(Mutex::new(String::new()));
        let error_output = Arc::clone(&errors);
        std::thread::spawn(move || {
            let mut bytes = [0u8; 2048];
            while let Ok(size) = stderr.read(&mut bytes) {
                if size == 0 {
                    break;
                }
                if let Ok(mut output) = error_output.lock() {
                    output.push_str(&String::from_utf8_lossy(&bytes[..size]));
                    if output.len() > 8192 {
                        let mut cut = output.len() - 8192;
                        while !output.is_char_boundary(cut) {
                            cut += 1;
                        }
                        output.drain(..cut);
                    }
                }
            }
        });
        let (tx, rx) = mpsc::sync_channel(1);
        std::thread::spawn(move || {
            let mut ready = false;
            for line in BufReader::new(stdout).lines().map_while(Result::ok) {
                if !ready {
                    if let Some(port) = line
                        .strip_prefix("COVERAGE_READY ")
                        .and_then(|p| p.trim().parse::<u16>().ok())
                    {
                        if port != 0 {
                            let _ = tx.send(port);
                            ready = true;
                        }
                    }
                }
            }
        });
        let port = rx
            .recv_timeout(Duration::from_secs(30))
            .map_err(|e| format!("执行内核未就绪：{e}\n{}", error_tail(&errors)))?;
        Ok(Self {
            process: Mutex::new(managed),
            client,
            base: format!("http://127.0.0.1:{port}"),
            token,
            closing: AtomicBool::new(false),
            ui_ready: AtomicBool::new(false),
            roots: Mutex::new(Vec::new()),
            operations: tokio::sync::Mutex::new(()),
            recovery: Mutex::new(None),
        })
    }

    pub async fn request(&self, route: &str, payload: Value) -> Result<Value, String> {
        let response = self
            .client
            .post(format!("{}{route}", self.base))
            .bearer_auth(&self.token)
            .json(&payload)
            .send()
            .await
            .map_err(|e| format!("执行内核通信失败：{e}"))?;
        let status = response.status();
        let body: Value = response
            .json()
            .await
            .map_err(|e| format!("无法读取执行内核响应：{e}"))?;
        if status.is_success() {
            Ok(body)
        } else {
            Err(body["error"].as_str().unwrap_or("操作失败").to_owned())
        }
    }

    pub async fn health(&self) -> Result<Value, String> {
        self.client
            .get(format!("{}/health", self.base))
            .bearer_auth(&self.token)
            .send()
            .await
            .map_err(|e| e.to_string())?
            .error_for_status()
            .map_err(|e| e.to_string())?
            .json()
            .await
            .map_err(|e| e.to_string())
    }

    pub fn recovery_active(&self) -> Result<bool, String> {
        let mut recovery = self.recovery.lock().map_err(|e| e.to_string())?;
        if let Some(child) = recovery.as_mut() {
            if child.try_wait().map_err(|e| e.to_string())?.is_none() {
                return Ok(true);
            }
            recovery.take();
        }
        Ok(false)
    }

    pub fn exited(&self) -> bool {
        self.process
            .lock()
            .map(|mut p| matches!(p.child.try_wait(), Ok(Some(_))))
            .unwrap_or(true)
    }

    pub fn shutdown(&self) {
        self.closing.store(true, Ordering::SeqCst);
        if let Ok(mut process) = self.process.lock() {
            process.shutdown();
        }
    }

    pub async fn busy(&self) -> Result<bool, String> {
        let state = self.request("/state", json!({"cursor": 0})).await?;
        Ok(matches!(
            state["status"].as_str(),
            Some("running" | "stopping")
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn trial_only_imports_when_its_database_does_not_exist() {
        let root = tempfile::tempdir().unwrap();
        let legacy = root.path().join("@coverage-loop/desktop");
        let data = root.path().join("trial");
        std::fs::create_dir_all(&legacy).unwrap();
        std::fs::write(legacy.join("workspace.db"), "old").unwrap();
        assert_eq!(
            legacy_workspace(root.path(), &data),
            Some(legacy.join("workspace.db"))
        );
        std::fs::create_dir_all(&data).unwrap();
        std::fs::write(data.join("workspace.db"), "new").unwrap();
        assert_eq!(legacy_workspace(root.path(), &data), None);
    }

    #[tokio::test]
    async fn real_java_handles_canonical_unicode_paths_and_stops_cleanly() {
        let Some(jar) = std::env::var_os("COVERAGE_TEST_ENGINE_JAR") else {
            return;
        };
        let data = tempfile::tempdir().unwrap();
        let resources = data.path().join("内核 空格");
        std::fs::create_dir(&resources).unwrap();
        let copied_jar = resources.join("coverage-loop-engine.jar");
        std::fs::copy(jar, &copied_jar).unwrap();
        // std canonicalize deliberately supplies the same Windows verbatim path
        // returned by Tauri in an installed build, unlike a normal JAVA_HOME path.
        let jar = copied_jar.canonicalize().unwrap();
        let java = std::env::var_os("JAVA_HOME")
            .map(|p| {
                PathBuf::from(p)
                    .join("bin")
                    .join(if cfg!(windows) { "java.exe" } else { "java" })
            })
            .unwrap_or_else(|| "java".into());
        let engine = Engine::start(EnginePaths {
            java,
            jar,
            data: data.path().canonicalize().unwrap(),
            legacy: None,
        })
        .unwrap();
        assert!(engine.health().await.unwrap()["version"].is_string());
        assert!(engine
            .request("/projects", json!({}))
            .await
            .unwrap()
            .is_array());
        assert_eq!(
            engine.request("/state", json!({})).await.unwrap()["status"],
            "idle"
        );
        let unauthorized = reqwest::Client::builder()
            .no_proxy()
            .build()
            .unwrap()
            .get(format!("{}/health", engine.base))
            .send()
            .await
            .unwrap();
        assert_eq!(unauthorized.status(), 401);
        assert!(engine.request("/not-a-route", json!({})).await.is_err());
        engine.shutdown();
        assert!(engine.exited());
        assert!(data.path().join("workspace.db").is_file());
    }
}
