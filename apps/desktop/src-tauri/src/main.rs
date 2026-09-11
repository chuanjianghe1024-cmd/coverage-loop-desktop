#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use coverage_loop_tauri::{engine, policy, recovery};
use engine::{Engine, EnginePaths};
use serde_json::{json, Value};
use std::{
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::Duration,
};
use tauri::{Manager, WebviewWindow};
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};
use tauri_plugin_opener::OpenerExt;

fn startup_trace(message: &str) {
    if let Some(report) = std::env::var_os("COVERAGE_SMOKE_REPORT") {
        use std::io::Write;
        let path = PathBuf::from(report).with_extension("log");
        if let Ok(mut file) = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(path)
        {
            let _ = writeln!(file, "{:?} {message}", std::time::SystemTime::now());
        }
    }
}

fn smoke_failure(message: &str) -> bool {
    startup_trace(message);
    if let Some(report) = std::env::var_os("COVERAGE_SMOKE_REPORT") {
        let _ = std::fs::write(
            report,
            serde_json::to_vec_pretty(&json!({"status":"failed","error":message}))
                .unwrap_or_default(),
        );
        true
    } else {
        false
    }
}

fn trusted(window: &WebviewWindow) -> Result<(), String> {
    if window.label() == "main" && window.url().is_ok_and(|url| policy::local_url(&url)) {
        Ok(())
    } else {
        Err("不受信任的桌面窗口".into())
    }
}

#[tauri::command]
async fn coverage_request(
    window: WebviewWindow,
    engine: tauri::State<'_, Arc<Engine>>,
    route: String,
    payload: Option<Value>,
) -> Result<Value, String> {
    trusted(&window)?;
    policy::validate_route(&route)?;
    let _operation = if matches!(route.as_str(), "/run/start" | "/history/delete") {
        let guard = engine.operations.lock().await;
        if engine.recovery_active()? {
            return Err("请先关闭正在恢复会话的终端，再执行此操作".into());
        }
        Some(guard)
    } else {
        None
    };
    let result = engine
        .request(&route, payload.unwrap_or_else(|| json!({})))
        .await?;
    startup_trace(&format!("bridge request completed: {route}"));
    if route == "/project/open" {
        let root = result["project"]["rootDirectory"]
            .as_str()
            .ok_or("工程目录无效")?;
        let root = PathBuf::from(root)
            .canonicalize()
            .map_err(|e| e.to_string())?;
        let mut roots = engine.roots.lock().map_err(|e| e.to_string())?;
        if !roots.contains(&root) {
            roots.push(root);
        }
    }
    engine.ui_ready.store(true, Ordering::SeqCst);
    Ok(result)
}

#[tauri::command]
async fn choose_path(window: WebviewWindow, kind: String) -> Result<Option<String>, String> {
    trusted(&window)?;
    if !matches!(
        kind.as_str(),
        "project" | "settings" | "jdk" | "maven" | "agent" | "repository"
    ) {
        return Err("无效的文件选择类型".into());
    }
    tauri::async_runtime::spawn_blocking(move || {
        let folder = matches!(kind.as_str(), "project" | "jdk" | "repository");
        let mut dialog = window
            .dialog()
            .file()
            .set_parent(&window)
            .set_title(if folder {
                "选择目录"
            } else {
                "选择文件"
            });
        if kind == "settings" {
            dialog = dialog.add_filter("Maven settings", &["xml"]);
        }
        let selected = if folder {
            dialog.blocking_pick_folder()
        } else {
            dialog.blocking_pick_file()
        };
        selected
            .map(|p| {
                p.into_path()
                    .map(|p| p.to_string_lossy().into_owned())
                    .map_err(|e| e.to_string())
            })
            .transpose()
    })
    .await
    .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn open_artifact(
    window: WebviewWindow,
    engine: tauri::State<'_, Arc<Engine>>,
    path: String,
) -> Result<(), String> {
    trusted(&window)?;
    let target = policy::artifact_path(
        &engine.roots.lock().map_err(|e| e.to_string())?,
        &PathBuf::from(path),
    )?;
    window
        .opener()
        .open_path(target.to_string_lossy(), None::<&str>)
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn recover_session(
    window: WebviewWindow,
    engine: tauri::State<'_, Arc<Engine>>,
    selector: Value,
) -> Result<Value, String> {
    trusted(&window)?;
    let _operation = engine.operations.lock().await;
    if engine.recovery_active()? {
        return Err("恢复终端已经打开".into());
    }
    let descriptor: recovery::Descriptor =
        serde_json::from_value(engine.request("/round/recovery", selector).await?)
            .map_err(|e| format!("会话记录不完整：{e}"))?;
    let child = recovery::launch(
        &descriptor,
        &engine.roots.lock().map_err(|e| e.to_string())?,
    )?;
    *engine.recovery.lock().map_err(|e| e.to_string())? = Some(child);
    Ok(json!({"sessionId": descriptor.session_id}))
}

fn request_quit(app: tauri::AppHandle, engine: Arc<Engine>, pending: Arc<AtomicBool>) {
    if pending.swap(true, Ordering::SeqCst) {
        return;
    }
    tauri::async_runtime::spawn(async move {
        let busy = engine.busy().await.unwrap_or(true);
        if busy {
            let handle = app.clone();
            app.dialog()
                .message("任务仍在运行，是否停止后退出？")
                .title("Coverage Loop")
                .buttons(MessageDialogButtons::OkCancelCustom(
                    "停止并退出".into(),
                    "继续运行".into(),
                ))
                .show(move |confirmed| {
                    if confirmed {
                        finish_quit(handle, engine);
                    } else {
                        pending.store(false, Ordering::SeqCst);
                    }
                });
        } else {
            finish_quit(app, engine);
        }
    });
}

fn finish_quit(app: tauri::AppHandle, engine: Arc<Engine>) {
    engine.closing.store(true, Ordering::SeqCst);
    tauri::async_runtime::spawn_blocking(move || {
        engine.shutdown();
        app.exit(0);
    });
}

/// Installed-package smoke test: starts the actual bundled Java runtime, opens a fixture
/// through the same bridge, then exits through the normal cleanup path. No user workspace is used.
async fn smoke(engine: &Engine, project: &str) -> Result<Value, String> {
    let health = engine.health().await?;
    let opened = engine
        .request("/project/open", json!({"rootPomPath":project}))
        .await?;
    let config = opened["config"].clone();
    engine
        .request("/config/save", json!({"config":config}))
        .await?;
    let recent = engine.request("/projects", json!({})).await?;
    let state = engine.request("/state", json!({"cursor":0})).await?;
    if recent.as_array().is_none_or(Vec::is_empty) || state["status"] != "idle" {
        return Err("安装包工作区验证失败".into());
    }
    Ok(
        json!({"shellVersion":env!("CARGO_PKG_VERSION"),"health":health,"project":opened["project"]["rootArtifactId"],"status":"passed","webviewBridge":true}),
    )
}

fn main() {
    startup_trace("native main entered");
    let result = tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _, _| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
                let _ = window.set_focus();
            }
        }))
        .plugin(tauri_plugin_dialog::init())
        .plugin(
            tauri_plugin_opener::Builder::new()
                .open_js_links_on_click(false)
                .build(),
        )
        .invoke_handler(tauri::generate_handler![
            coverage_request,
            choose_path,
            open_artifact,
            recover_session
        ])
        .setup(|app| {
            startup_trace("Tauri setup entered");
            let handle = app.handle().clone();
            let resources = app.path().resource_dir()?;
            let config_dir = app.path().config_dir()?;
            let data = std::env::var_os("COVERAGE_DATA_DIR")
                .map(PathBuf::from)
                .unwrap_or(app.path().app_data_dir()?);
            let paths = EnginePaths::resolve(&resources, &config_dir, data);
            startup_trace(&format!(
                "resources={} java={} jar={} data={}",
                resources.display(),
                paths.java.display(),
                paths.jar.display(),
                paths.data.display()
            ));
            tauri::async_runtime::spawn(async move {
                let started =
                    tauri::async_runtime::spawn_blocking(move || Engine::start(paths)).await;
                let started = match started {
                    Ok(value) => value,
                    Err(e) => Err(e.to_string()),
                };
                let engine = match started {
                    Ok(engine) => Arc::new(engine),
                    Err(message) => {
                        if smoke_failure(&message) {
                            handle.exit(1);
                            return;
                        }
                        let quit = handle.clone();
                        handle
                            .dialog()
                            .message(message)
                            .title("Coverage Loop 启动失败")
                            .kind(MessageDialogKind::Error)
                            .show(move |_| quit.exit(1));
                        return;
                    }
                };
                startup_trace("Java engine ready");
                handle.manage(Arc::clone(&engine));
                startup_trace("creating WebView window");
                let built = tauri::WebviewWindowBuilder::new(
                    &handle,
                    "main",
                    tauri::WebviewUrl::App("index.html".into()),
                )
                .title("Coverage Loop Tauri")
                .inner_size(1440.0, 960.0)
                .min_inner_size(1080.0, 760.0)
                .decorations(false)
                .theme(Some(tauri::Theme::Light))
                .background_color(tauri::window::Color(244, 249, 244, 255))
                .on_navigation(policy::local_url)
                .on_new_window(|_, _| tauri::webview::NewWindowResponse::Deny)
                .build();
                let window = match built {
                    Ok(window) => window,
                    Err(e) => {
                        smoke_failure(&format!("无法创建窗口：{e}"));
                        engine.shutdown();
                        eprintln!("无法创建窗口：{e}");
                        handle.exit(1);
                        return;
                    }
                };
                startup_trace("WebView window created");
                let pending = Arc::new(AtomicBool::new(false));
                let close_engine = Arc::clone(&engine);
                let close_handle = handle.clone();
                window.on_window_event(move |event| {
                    if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                        if !close_engine.closing.load(Ordering::SeqCst) {
                            api.prevent_close();
                            request_quit(
                                close_handle.clone(),
                                Arc::clone(&close_engine),
                                Arc::clone(&pending),
                            );
                        }
                    }
                });
                if let (Some(project), Some(report_path)) = (
                    std::env::var_os("COVERAGE_SMOKE_PROJECT"),
                    std::env::var_os("COVERAGE_SMOKE_REPORT"),
                ) {
                    let deadline = std::time::Instant::now() + Duration::from_secs(30);
                    while !engine.ui_ready.load(Ordering::SeqCst)
                        && std::time::Instant::now() < deadline
                    {
                        tokio::time::sleep(Duration::from_millis(100)).await;
                    }
                    let result = if engine.ui_ready.load(Ordering::SeqCst) {
                        startup_trace("WebView IPC ready; checking workspace");
                        smoke(&engine, &project.to_string_lossy()).await
                    } else {
                        Err("WebView 页面未能通过 Tauri 桥接访问执行内核".into())
                    };
                    let mut code = if result.is_ok() { 0 } else { 1 };
                    let report = result.unwrap_or_else(|e| json!({"status":"failed","error":e}));
                    startup_trace(&format!("smoke result: {report}"));
                    if std::fs::write(
                        report_path,
                        serde_json::to_vec_pretty(&report).unwrap_or_default(),
                    )
                    .is_err()
                    {
                        code = 1;
                    }
                    engine.shutdown();
                    startup_trace("Java engine stopped; exiting shell");
                    handle.exit(code);
                    return;
                }
                loop {
                    tokio::time::sleep(Duration::from_millis(500)).await;
                    if engine.closing.load(Ordering::SeqCst) {
                        break;
                    }
                    if engine.exited() {
                        engine.closing.store(true, Ordering::SeqCst);
                        let quit = handle.clone();
                        handle
                            .dialog()
                            .message("执行内核已退出，请重新启动应用。已有任务记录保存在本机。")
                            .kind(MessageDialogKind::Error)
                            .show(move |_| quit.exit(1));
                        break;
                    }
                }
            });
            Ok(())
        })
        .build(tauri::generate_context!());
    match result {
        Ok(app) => app.run(|handle, event| {
            if let tauri::RunEvent::Exit = event {
                if let Some(engine) = handle.try_state::<Arc<Engine>>() {
                    engine.shutdown();
                }
            }
        }),
        Err(e) => {
            smoke_failure(&format!("Coverage Loop 无法启动：{e}"));
            eprintln!("Coverage Loop 无法启动：{e}");
            std::process::exit(1);
        }
    }
}
