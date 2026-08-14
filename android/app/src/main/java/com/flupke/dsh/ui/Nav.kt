package com.flupke.dsh.ui

/** 导航：主界面=会话列表（DSH Web 风格）→ 会话详情；设置为独立 Stack。 */
sealed interface Screen : java.io.Serializable {
    data object Home : Screen
    data object Conversation : Screen
    data object Settings : Screen
    data class SettingsSub(val page: SettingsPage) : Screen
}

enum class SettingsPage : java.io.Serializable { Connection, Appearance, Conversation, Plugins, About }
