//! SenzSpace profile-core (S0).
//!
//! Registry Space (= Profile). Aturan yang ditegakkan di sini, bukan cuma di UI:
//! - total Space aktif maksimal 10 (D1, terkunci)
//! - preset awal 7 Space: Claude 1-6 + AI Studio (D2, terkunci)
//! - hapus Space membebaskan slotnya; Space ke-11 ditolak dengan error jelas.
//!
//! Sesi login sendiri TIDAK disimpan di crate ini — sesi hidup di WebView
//! Android per Space (data directory terpisah per proses). Crate ini hanya
//! mengatur registry, nama, warna, situs utama, dan batas jumlah Space.

/// Batas maksimal Space (D1).
pub const MAX_SPACES: usize = 10;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Space {
    pub id: String,
    pub name: String,
    pub color: String,
    pub home_url: String,
}

impl Space {
    pub fn new(id: &str, name: &str, color: &str, home_url: &str) -> Self {
        Self {
            id: id.to_string(),
            name: name.to_string(),
            color: color.to_string(),
            home_url: home_url.to_string(),
        }
    }
}

/// Preset awal 7 Space (D2). Semua bisa di-rename / dihapus pengguna.
pub fn default_spaces() -> Vec<Space> {
    let mut v = Vec::new();
    for i in 1..=6 {
        v.push(Space::new(
            &format!("claude-{}", i),
            &format!("Claude {}", i),
            "#5B5BD6",
            "https://claude.ai",
        ));
    }
    v.push(Space::new(
        "ai-studio",
        "AI Studio",
        "#1A73E8",
        "https://aistudio.google.com",
    ));
    v
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RegistryError {
    MaxSpacesReached,
    DuplicateId,
    NotFound,
}

impl std::fmt::Display for RegistryError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            RegistryError::MaxSpacesReached => write!(f, "Maksimal 10 Space"),
            RegistryError::DuplicateId => write!(f, "Space dengan id itu sudah ada"),
            RegistryError::NotFound => write!(f, "Space tidak ditemukan"),
        }
    }
}

impl std::error::Error for RegistryError {}

#[derive(Debug, Default)]
pub struct Registry {
    spaces: Vec<Space>,
}

impl Registry {
    pub fn new() -> Self {
        Self { spaces: default_spaces() }
    }

    pub fn empty() -> Self {
        Self { spaces: Vec::new() }
    }

    pub fn spaces(&self) -> &[Space] {
        &self.spaces
    }

    pub fn count(&self) -> usize {
        self.spaces.len()
    }

    pub fn can_add(&self) -> bool {
        self.spaces.len() < MAX_SPACES
    }

    pub fn add(&mut self, space: Space) -> Result<(), RegistryError> {
        if self.spaces.iter().any(|s| s.id == space.id) {
            return Err(RegistryError::DuplicateId);
        }
        if self.spaces.len() >= MAX_SPACES {
            return Err(RegistryError::MaxSpacesReached);
        }
        self.spaces.push(space);
        Ok(())
    }

    pub fn rename(&mut self, id: &str, new_name: &str) -> Result<(), RegistryError> {
        match self.spaces.iter_mut().find(|s| s.id == id) {
            Some(s) => {
                s.name = new_name.to_string();
                Ok(())
            }
            None => Err(RegistryError::NotFound),
        }
    }

    /// Hapus Space; slotnya bisa dipakai lagi untuk Space baru.
    pub fn remove(&mut self, id: &str) -> Result<Space, RegistryError> {
        match self.spaces.iter().position(|s| s.id == id) {
            Some(i) => Ok(self.spaces.remove(i)),
            None => Err(RegistryError::NotFound),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn preset_awal_tujuh_space() {
        let r = Registry::new();
        assert_eq!(r.count(), 7);
        assert_eq!(r.spaces()[0].name, "Claude 1");
        assert_eq!(r.spaces()[6].name, "AI Studio");
    }

    #[test]
    fn bisa_tambah_sampai_sepuluh_lalu_ditolak() {
        let mut r = Registry::new();
        for i in 8..=10 {
            assert!(r.add(Space::new(&format!("extra-{}", i), "Extra", "#000000", "https://example.com")).is_ok());
        }
        assert_eq!(r.count(), 10);
        assert!(!r.can_add());
        let err = r.add(Space::new("extra-11", "Extra 11", "#000000", "https://example.com")).unwrap_err();
        assert_eq!(err.to_string(), "Maksimal 10 Space");
    }

    #[test]
    fn hapus_membebaskan_slot() {
        let mut r = Registry::new();
        for i in 8..=10 {
            r.add(Space::new(&format!("extra-{}", i), "Extra", "#000000", "https://example.com")).unwrap();
        }
        r.remove("extra-8").unwrap();
        assert!(r.can_add());
        assert!(r.add(Space::new("baru", "Baru", "#000000", "https://example.com")).is_ok());
        assert_eq!(r.count(), 10);
    }

    #[test]
    fn rename_tidak_merusak_space_lain() {
        let mut r = Registry::new();
        r.rename("claude-1", "Claude Utama").unwrap();
        assert_eq!(r.spaces()[0].name, "Claude Utama");
        assert_eq!(r.spaces()[1].name, "Claude 2");
    }

    #[test]
    fn hapus_space_tidak_ada_gagal_jelas() {
        let mut r = Registry::new();
        assert_eq!(r.remove("tidak-ada").unwrap_err(), RegistryError::NotFound);
    }
}
