#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
use std::{io::{BufRead, BufReader, Write}, path::PathBuf, process::{Child, ChildStdin, ChildStdout, Command, Stdio}, sync::Mutex};
use serde_json::{json, Value};
use tauri::{Manager, menu::{Menu, MenuItem}, tray::{TrayIconBuilder, TrayIconEvent, MouseButton, MouseButtonState}};

struct Service { child: Child, input: ChildStdin, output: BufReader<ChildStdout> }
fn runtime_directory() -> Result<PathBuf, std::io::Error> {
    let directory = std::env::current_exe()?.parent().unwrap().to_path_buf();
    #[cfg(target_os = "macos")]
    {
        let resources = directory.parent().unwrap().join("Resources");
        if resources.join("remote-manager.json").is_file() { return Ok(resources); }
    }
    Ok(directory)
}
impl Service {
    fn start(data: PathBuf) -> Result<Self, Box<dyn std::error::Error>> {
        let directory = runtime_directory()?;
        let manifest = if let Ok(value) = std::env::var("DEFOLD_REMOTE_MANIFEST") { PathBuf::from(value) } else {
            let config: Value = serde_json::from_slice(&std::fs::read(directory.join("remote-manager.json"))?)?;
            directory.join(config["manifest"].as_str().ok_or("Missing runtime manifest")?)
        };
        let runtime: Value = serde_json::from_slice(&std::fs::read(&manifest)?)?;
        let root = manifest.parent().ok_or("Invalid runtime manifest")?;
        let path = |key: &str| -> Result<PathBuf, Box<dyn std::error::Error>> { Ok(root.join(runtime[key].as_str().ok_or("Invalid runtime path")?)) };
        std::fs::create_dir_all(&data)?;
        let mut command = Command::new(path("java")?);
        command.args(["--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true", "-cp"])
            .arg(std::env::join_paths([path("classes")?, path("jar")?])?)
            .arg("com.defold.editor.RemoteManager").arg(&manifest).arg(&data)
            .stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(std::fs::File::create(data.join("manager.log"))?);
        #[cfg(windows)] { use std::os::windows::process::CommandExt; command.creation_flags(0x08000000); }
        let mut child = command.spawn()?;
        let input = child.stdin.take().ok_or("Missing service input")?;
        let output = BufReader::new(child.stdout.take().ok_or("Missing service output")?);
        Ok(Self {child,input,output})
    }
    fn call(&mut self, method: &str, params: Value) -> Result<Value,String> {
        if self.child.try_wait().map_err(|e|e.to_string())?.is_some() { return Err("后台服务已退出，请重启管理器并查看 manager.log".into()) }
        writeln!(self.input,"{}",json!({"method":method,"params":params})).map_err(|e|e.to_string())?;
        self.input.flush().map_err(|e|e.to_string())?;
        let mut line=String::new(); self.output.read_line(&mut line).map_err(|e|e.to_string())?;
        let reply:Value=serde_json::from_str(&line).map_err(|_|"后台服务未返回有效结果，请查看 manager.log".to_string())?;
        if reply["ok"]==true { Ok(reply["result"].clone()) } else { Err(reply["error"].as_str().unwrap_or("操作失败").into()) }
    }
}
struct Backend(Mutex<Service>);
#[tauri::command]
async fn rpc(app: tauri::AppHandle, method: String, params: Value) -> Result<Value,String> {
    tauri::async_runtime::spawn_blocking(move || {
        let state=app.state::<Backend>();
        let result=state.0.lock().map_err(|_|"后台服务忙".to_string())?.call(&method,params);
        result
    }).await.map_err(|e|e.to_string())?
}
#[tauri::command]
async fn choose_project() -> Result<Option<String>,String> {
    Ok(rfd::AsyncFileDialog::new().set_title("选择包含 game.project 的工程目录").pick_folder().await.map(|p|p.path().to_string_lossy().into_owned()))
}
#[tauri::command]
fn qr_svg(value: String) -> Result<String,String> {
    if value.len()>2048 {return Err("二维码过长".into())}
    Ok(qrcode::QrCode::new(value).map_err(|e|e.to_string())?.render::<qrcode::render::svg::Color>().min_dimensions(320,320).build())
}
#[tauri::command]
fn hide(app: tauri::AppHandle) { if let Some(w)=app.get_webview_window("main"){let _=w.hide();} }
#[tauri::command]
fn quit(app: tauri::AppHandle) { app.exit(0); }
fn show(app:&tauri::AppHandle){if let Some(w)=app.get_webview_window("main"){let _=w.show();let _=w.unminimize();let _=w.set_focus();}}
fn main() {
    let app=tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app,_,_|show(app)))
        .invoke_handler(tauri::generate_handler![rpc,choose_project,qr_svg,hide,quit])
        .setup(|app| {
            let config=runtime_directory()?.join("remote-manager.json");
            let configured:Value=std::fs::read(config).ok().and_then(|bytes|serde_json::from_slice(&bytes).ok()).unwrap_or(Value::Null);
            let directory = std::env::var("DEFOLD_REMOTE_DATA").ok().or_else(||configured["dataDirectory"].as_str().map(str::to_owned))
                .map(PathBuf::from).unwrap_or(app.path().app_local_data_dir()?);
            app.manage(Backend(Mutex::new(Service::start(directory)?)));
            let show_item=MenuItem::with_id(app,"show","打开 Remote Manager",true,None::<&str>)?;
            let quit_item=MenuItem::with_id(app,"quit","停止全部游戏并退出",true,None::<&str>)?;
            let menu=Menu::with_items(app,&[&show_item,&quit_item])?;
            let mut pixels=vec![0u8;32*32*4];
            for y in 3..29 {for x in 3..29 {let i=(y*32+x)*4;pixels[i..i+4].copy_from_slice(if x>10&&x<22&&y>9&&y<23 {&[245,248,255,255]}else{&[59,121,211,255]});}}
            TrayIconBuilder::new().icon(tauri::image::Image::new_owned(pixels,32,32)).tooltip("Defold Remote Manager").menu(&menu).show_menu_on_left_click(false)
                .on_menu_event(|app,e|match e.id.as_ref(){"show"=>show(app),"quit"=>app.exit(0),_=>{}})
                .on_tray_icon_event(|tray,event|if matches!(event,TrayIconEvent::Click{button:MouseButton::Left,button_state:MouseButtonState::Up,..}){show(tray.app_handle())}).build(app)?;
            Ok(())
        })
        .on_window_event(|window,event|if let tauri::WindowEvent::CloseRequested{api,..}=event{api.prevent_close();let _=window.hide();})
        .build(tauri::generate_context!()).expect("Cannot start Defold Remote Manager");
    app.run(|app,event|if let tauri::RunEvent::Exit=event {
        if let Some(state)=app.try_state::<Backend>() { if let Ok(mut service)=state.0.lock(){let _=writeln!(service.input,"{{\"method\":\"quit\"}}");let _=service.input.flush();let _=service.child.wait();} }
    });
}
