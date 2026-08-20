//! Process-wide session registry keyed by the session id the session itself
//! assigned. Every exported call resolves its session here first; the id
//! strings come from the same global counter the Rust session layer uses.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use tiqian_precompute::session::FontSession;

static SESSIONS: OnceLock<Mutex<HashMap<String, FontSession>>> = OnceLock::new();

fn sessions() -> &'static Mutex<HashMap<String, FontSession>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Registers a created session and returns its id.
pub fn insert(session: FontSession) -> String {
    let id = session.session_id.clone();
    let mut map = sessions().lock().expect("font session registry mutex poisoned");
    map.insert(id.clone(), session);
    id
}

/// Runs `call` with the session for `id`, holding the registry lock.
pub fn with_session<T>(
    id: &str,
    call: impl FnOnce(&mut FontSession) -> T,
) -> Result<T, String> {
    let mut map = sessions().lock().expect("font session registry mutex poisoned");
    match map.get_mut(id) {
        Some(session) => Ok(call(session)),
        None => Err(format!("UnknownFontSession:{id}")),
    }
}
