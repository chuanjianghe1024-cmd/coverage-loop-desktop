use std::path::{Path, PathBuf};

pub fn validate_route(route: &str) -> Result<(), String> {
    match route {
        "/round/records"
        | "/round/file"
        | "/statistics/configs"
        | "/statistics/save"
        | "/statistics/delete"
        | "/statistics/preview"
        | "/projects"
        | "/project/open"
        | "/config/save"
        | "/config/load"
        | "/command/preview"
        | "/run/start"
        | "/run/stop"
        | "/state"
        | "/history"
        | "/history/detail"
        | "/history/delete" => Ok(()),
        _ => Err("未知桌面操作".into()),
    }
}

pub fn artifact_path(roots: &[PathBuf], input: &Path) -> Result<PathBuf, String> {
    let target = input.canonicalize().map_err(|e| e.to_string())?;
    if roots.iter().any(|root| {
        root.join(".coverage-loop")
            .canonicalize()
            .is_ok_and(|evidence| target.starts_with(evidence))
    }) {
        Ok(target)
    } else {
        Err("只能打开当前工程的 Coverage Loop 运行记录".into())
    }
}

pub fn project_root(roots: &[PathBuf], input: &Path) -> Result<PathBuf, String> {
    let target = input.canonicalize().map_err(|e| e.to_string())?;
    if roots.contains(&target) {
        Ok(target)
    } else {
        Err("请先打开会话对应的工程".into())
    }
}

pub fn local_url(url: &url::Url) -> bool {
    (url.scheme() == "tauri" && url.host_str() == Some("localhost"))
        || (matches!(url.scheme(), "http" | "https") && url.host_str() == Some("tauri.localhost"))
        || (cfg!(debug_assertions)
            && url.scheme() == "http"
            && url.host_str() == Some("127.0.0.1")
            && url.port() == Some(5173))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn routes_do_not_expose_health_or_raw_recovery() {
        assert!(validate_route("/run/stop").is_ok());
        for route in [
            "/health",
            "/round/recovery",
            "http://evil",
            "/state?x",
            "/../state",
        ] {
            assert!(validate_route(route).is_err());
        }
    }

    #[test]
    fn evidence_is_confined_to_open_projects() {
        let temp = tempfile::tempdir().unwrap();
        let root = temp.path().canonicalize().unwrap();
        let evidence = root.join(".coverage-loop");
        std::fs::create_dir(&evidence).unwrap();
        std::fs::write(evidence.join("maven.log"), "ok").unwrap();
        std::fs::write(root.join("private.txt"), "private").unwrap();
        assert!(artifact_path(std::slice::from_ref(&root), &evidence.join("maven.log")).is_ok());
        assert!(artifact_path(std::slice::from_ref(&root), &root.join("private.txt")).is_err());
        assert!(artifact_path(&[], &evidence).is_err());
        #[cfg(unix)]
        {
            std::os::unix::fs::symlink(root.join("private.txt"), evidence.join("escape")).unwrap();
            assert!(artifact_path(&[root], &evidence.join("escape")).is_err());
        }
    }

    #[test]
    fn navigation_accepts_only_the_local_application() {
        assert!(local_url(&"http://tauri.localhost/".parse().unwrap()));
        for url in [
            "https://example.org",
            "http://tauri.localhost.evil",
            "file:///etc/passwd",
        ] {
            assert!(!local_url(&url.parse().unwrap()));
        }
    }
}
