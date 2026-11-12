#![cfg(target_arch = "wasm32")]

use std::sync::Arc;

use flare_core::application::services;
use wasm_bindgen_test::{wasm_bindgen_test, wasm_bindgen_test_configure};

wasm_bindgen_test_configure!(run_in_browser);

#[wasm_bindgen_test]
async fn initializes_through_the_browser_export() {
    let worker_url = web_sys::Url::new_with_base(
        "/target/database-web/worker.js",
        &web_sys::window().unwrap().location().href().unwrap(),
    )
    .unwrap()
    .href();
    flare_ffi::initialize(worker_url.clone()).await.unwrap();
    let first = services().unwrap();
    flare_ffi::initialize(worker_url).await.unwrap();
    assert!(Arc::ptr_eq(&first, &services().unwrap()));
}
