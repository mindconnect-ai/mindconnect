- **plugins:** **a plugin can read named settings.** A plugin declares the
  settings it reads (`Plugin.settings()`) and reads them from the registry
  (`PluginRegistry.setting`). Each has a default or is required; a required
  one that is missing or unreadable stops the host when it starts, with every
  problem named at once. A setting can stand in for a shared one
  (`skills.persistence`, else `persistence`). The host provides the values as
  a `SettingSource`. Nothing reads its configuration this way yet.
