package com.jarvis.assistant.automation.accessibility

/**
 * Semantic classification of a UI element on screen.
 * Maps onto both common Android view classes and Jetpack Compose semantics.
 */
enum class UiElementType(val displayName: String) {
    UNKNOWN("Unknown"),
    TEXT("Text"),
    BUTTON("Button"),
    TEXT_FIELD("Text field"),
    CHECKBOX("Checkbox"),
    RADIO_BUTTON("Radio button"),
    SWITCH("Switch"),
    LIST("List"),
    LIST_ITEM("List item"),
    CARD("Card"),
    IMAGE("Image"),
    TAB("Tab"),
    TAB_BAR("Tab bar"),
    MENU("Menu"),
    DIALOG("Dialog"),
    BOTTOM_SHEET("Bottom sheet"),
    NAVIGATION_BAR("Navigation bar"),
    SCROLLABLE_AREA("Scrollable area"),
    PROGRESS("Progress"),
    WEBVIEW("Web view"),
    COMPOSE_VIEW("Compose view")
}
