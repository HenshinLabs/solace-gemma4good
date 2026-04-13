package com.masterllm.core.ui.components

import android.graphics.Color
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.TextView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.linkify.LinkifyPlugin

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarkdownMessageText(
    markdown: String,
    textColor: Int,
    modifier: Modifier = Modifier,
    textSizeSp: Float = 14f,
    onLongClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(CorePlugin.create())
            .usePlugin(LinkifyPlugin.create(Linkify.WEB_URLS))
            .usePlugin(
                object : AbstractMarkwonPlugin() {
                    override fun configureTheme(builder: MarkwonTheme.Builder) {
                        builder
                            .codeBackgroundColor(Color.BLACK)
                            .codeTextColor(Color.WHITE)
                            .codeBlockBackgroundColor(Color.BLACK)
                            .codeBlockTextColor(Color.WHITE)
                    }
                }
            )
            .build()
    }

    val rendered = remember(markdown, markwon) {
        markwon.render(markwon.parse(markdown))
    }

    val contentModifier = if (onLongClick != null) {
        modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
    } else {
        modifier
    }

    AndroidView(
        modifier = contentModifier,
        factory = {
            TextView(it).apply {
                autoLinkMask = Linkify.WEB_URLS
                linksClickable = true
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
                textSize = textSizeSp
                setTextColor(textColor)
            }
        },
        update = { view ->
            view.textSize = textSizeSp
            view.setTextColor(textColor)
            view.text = rendered
        },
    )
}
