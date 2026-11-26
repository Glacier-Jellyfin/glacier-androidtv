package io.github.glacier_jellyfin.androidtv.core.designsystem

/**
 * One level of a [Breadcrumb]. With an [onClick] the level takes focus and opens that place;
 * without one it is plain text: the current page, or a label that leads nowhere.
 * [contentDescription] is what a screen reader says for a level that can be opened.
 */
data class BreadcrumbLevel(
    val label: String,
    val onClick: (() -> Unit)? = null,
    val contentDescription: String? = null,
)

/** Whether any level of the breadcrumb can take focus. */
val List<BreadcrumbLevel>.hasFocusable: Boolean get() = any { it.onClick != null }
