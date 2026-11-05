package com.jarvis.assistant.ui.compose.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.R

/**
 * The AndroLLM logo mark. Rendered from the shared [com.jarvis.assistant.R.drawable.androllm_logo]
 * asset so the same mark appears everywhere — splash, auth, settings, drawer and home.
 */
@Composable
fun CloudBugdroidLogo(
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    showMoon: Boolean = true
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.androllm_logo),
            contentDescription = "AndroLLM logo",
            modifier = Modifier.size(size),
            contentScale = ContentScale.Fit
        )
    }
}
