package com.interpretertrainer.app.ui.screens

/**
 * Produces the JavaScript template-expression text used inside the injected raw Kotlin script.
 * Keeping it here prevents Kotlin string interpolation from consuming the JS expression.
 */
internal fun nativePracticeContext(): String = "\${nativePracticeContext()}"
