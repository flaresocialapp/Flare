use super::{InitConfig, InitError};
use flare_database::{AgentDatabase, AppDatabase, CacheDatabase};

pub(super) async fn open_cache(config: &InitConfig) -> Result<CacheDatabase, InitError> {
    #[cfg(not(target_arch = "wasm32"))]
    {
        let config = config.clone();
        let path = blocking::unblock(move || cache_path(&config)).await?;
        Ok(CacheDatabase::open(path).await?)
    }
    #[cfg(target_arch = "wasm32")]
    {
        Ok(CacheDatabase::open("cache", worker_url(config)?).await?)
    }
}

#[cfg(not(target_arch = "wasm32"))]
fn cache_path(config: &InitConfig) -> Result<std::path::PathBuf, flare_database::Error> {
    use flare_database::Error;
    use std::path::PathBuf;
    #[cfg(target_os = "android")]
    let root = config
        .android_cache_dir
        .as_ref()
        .map(PathBuf::from)
        .ok_or_else(|| Error::InvalidRequest("Android cacheDir is missing".into()))?;
    #[cfg(not(target_os = "android"))]
    let _ = config;
    #[cfg(target_vendor = "apple")]
    let root = objc2::rc::autoreleasepool(|_| {
        use objc2_foundation::{NSFileManager, NSSearchPathDirectory, NSSearchPathDomainMask};
        let url = NSFileManager::defaultManager()
            .URLForDirectory_inDomain_appropriateForURL_create_error(
                NSSearchPathDirectory::CachesDirectory,
                NSSearchPathDomainMask::UserDomainMask,
                None,
                true,
            )
            .map_err(|e| std::io::Error::other(e.to_string()))?;
        url.path()
            .map(|p| PathBuf::from(p.to_string()).join("Flare"))
            .ok_or_else(|| Error::InvalidRequest("Apple cache directory unavailable".into()))
    })?;
    #[cfg(any(windows, target_os = "linux"))]
    let root = dirs::cache_dir()
        .map(|p| p.join("Flare"))
        .ok_or_else(|| Error::InvalidRequest("cache directory unavailable".into()))?;
    #[cfg(not(any(
        target_os = "android",
        target_vendor = "apple",
        windows,
        target_os = "linux"
    )))]
    let root: PathBuf = return Err(Error::InvalidRequest("cache directory unsupported".into()));
    if !root.is_absolute() {
        return Err(Error::InvalidRequest(
            "cache directory must be absolute".into(),
        ));
    }
    Ok(root.join("cache.db"))
}

pub(super) async fn open_agent(config: &InitConfig) -> Result<AgentDatabase, InitError> {
    #[cfg(not(target_arch = "wasm32"))]
    {
        let config = config.clone();
        let path =
            blocking::unblock(move || resolve(&config).map(|path| path.with_file_name("agent.db")))
                .await?;
        Ok(AgentDatabase::open(path).await?)
    }
    #[cfg(target_arch = "wasm32")]
    {
        Ok(AgentDatabase::open("agent", worker_url(config)?).await?)
    }
}

#[cfg(target_arch = "wasm32")]
fn worker_url(config: &InitConfig) -> Result<&str, InitError> {
    config
        .web_database_worker_url
        .as_deref()
        .ok_or_else(|| InitError::InvalidConfiguration {
            message: "database worker URL is missing".into(),
        })
}

pub(super) async fn open(config: &InitConfig) -> Result<AppDatabase, InitError> {
    #[cfg(not(target_arch = "wasm32"))]
    {
        let config = config.clone();
        let path = blocking::unblock(move || resolve(&config)).await?;
        Ok(AppDatabase::open(path).await?)
    }
    #[cfg(target_arch = "wasm32")]
    {
        Ok(AppDatabase::open("app", worker_url(config)?).await?)
    }
}

#[cfg(not(target_arch = "wasm32"))]
fn resolve(config: &InitConfig) -> Result<std::path::PathBuf, flare_database::Error> {
    use flare_database::Error;
    use std::path::PathBuf;

    #[cfg(target_os = "android")]
    let root = config
        .android_database_dir
        .as_deref()
        .map(PathBuf::from)
        .ok_or_else(|| Error::InvalidRequest("Android database directory is missing".into()))?;
    #[cfg(not(target_os = "android"))]
    let _ = config;

    #[cfg(target_os = "ios")]
    let root = objc2::rc::autoreleasepool(|_| {
        use objc2_foundation::{NSFileManager, NSSearchPathDirectory, NSSearchPathDomainMask};
        let url = NSFileManager::defaultManager()
            .URLForDirectory_inDomain_appropriateForURL_create_error(
                NSSearchPathDirectory::ApplicationSupportDirectory,
                NSSearchPathDomainMask::UserDomainMask,
                None,
                true,
            )
            .map_err(|error| std::io::Error::other(error.to_string()))?;
        url.path()
            .map(|path| PathBuf::from(path.to_string()).join("databases"))
            .ok_or_else(|| {
                Error::InvalidRequest("iOS Application Support directory unavailable".into())
            })
    })?;

    #[cfg(target_os = "macos")]
    let root = objc2::rc::autoreleasepool(|_| {
        PathBuf::from(objc2_foundation::NSHomeDirectory().to_string()).join(".flare")
    });

    #[cfg(target_os = "linux")]
    let root = {
        use nix::unistd::{Uid, User};
        // Match the JVM's account lookup before falling back to HOME.
        User::from_uid(Uid::current())
            .map_err(std::io::Error::from)?
            .map(|user| user.dir)
            .filter(|home| home.as_os_str().len() > 1)
            .or_else(|| std::env::var_os("HOME").map(PathBuf::from))
            .map(|home| home.join(".flare"))
            .ok_or_else(|| Error::InvalidRequest("Linux user home directory unavailable".into()))?
    };

    #[cfg(windows)]
    let root = dirs::home_dir()
        .map(|home| home.join(".flare"))
        .ok_or_else(|| {
            Error::InvalidRequest("Windows user profile directory unavailable".into())
        })?;

    #[cfg(not(any(
        target_os = "android",
        target_os = "ios",
        target_os = "macos",
        target_os = "linux",
        windows
    )))]
    let root: PathBuf = return Err(Error::InvalidRequest(
        "database directory unsupported on this platform".into(),
    ));

    if !root.is_absolute() {
        return Err(Error::InvalidRequest(
            "database directory must be absolute".into(),
        ));
    }
    Ok(root.join("app.db"))
}
