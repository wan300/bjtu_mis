package cn.edu.bjtu.mis.ui

import cn.edu.bjtu.mis.ui.theme.AppAppearancePreferences
import cn.edu.bjtu.mis.ui.theme.AppUiStyle

enum class UiStyleChangeOutcome {
    Applied,
    Undone,
}

suspend fun applyUiStyleChangeWithUndo(
    previousAppearance: AppAppearancePreferences,
    nextStyle: AppUiStyle,
    onPreview: (AppAppearancePreferences) -> Unit,
    persist: suspend (AppUiStyle) -> Unit,
    showUndo: suspend (message: String) -> Boolean,
): UiStyleChangeOutcome {
    val nextAppearance = previousAppearance.copy(uiStyle = nextStyle)
    onPreview(nextAppearance)
    persist(nextStyle)
    val undone = showUndo(
        if (nextStyle == AppUiStyle.Apple) {
            "已切换到 Apple 风格界面"
        } else {
            "已切换到经典界面"
        },
    )
    if (!undone) return UiStyleChangeOutcome.Applied

    onPreview(previousAppearance)
    persist(previousAppearance.uiStyle)
    return UiStyleChangeOutcome.Undone
}
