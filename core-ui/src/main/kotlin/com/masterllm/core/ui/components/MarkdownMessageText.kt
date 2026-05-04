package com.masterllm.core.ui.components

import android.graphics.Color
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.TextView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
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
    val clipboard = LocalClipboardManager.current

    val codeBlocks = remember(markdown) {
        Regex("```(?:\\w+)?\\n([\\s\\S]*?)```").findAll(markdown).map { it.groupValues[1].trim() }.toList()
    }

    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(CorePlugin.create())
            .usePlugin(LinkifyPlugin.create(Linkify.WEB_URLS))
            .usePlugin(
                object : AbstractMarkwonPlugin() {
                    override fun configureTheme(builder: MarkwonTheme.Builder) {
                        builder
                            .codeBackgroundColor(Color.rgb(30, 30, 30))
                            .codeTextColor(Color.rgb(212, 212, 212))
                            .codeBlockBackgroundColor(Color.rgb(30, 30, 30))
                            .codeBlockTextColor(Color.rgb(212, 212, 212))
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

    Column(modifier = contentModifier) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = {
                TextView(it).apply {
                    autoLinkMask = Linkify.WEB_URLS
                    linksClickable = true
                    movementMethod = LinkMovementMethod.getInstance()
                    setTextIsSelectable(true)
                    textSize = textSizeSp
                    setTextColor(textColor)
                    setLineSpacing(2f, 1f)
                }
            },
            update = { view ->
                view.textSize = textSizeSp
                view.setTextColor(textColor)
                view.text = rendered
            },
        )

        codeBlocks.forEachIndexed { _, code ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                shape = RoundedCornerShape(8.dp),
                color = androidx.compose.ui.graphics.Color(0xFF1E1E1E),
                tonalElevation = 0.dp,
            ) {
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(code)) },
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy code",
                        tint = androidx.compose.ui.graphics.Color(0xFF888888),
                    )
                    Text(
                        text = "Copy code",
                        color = androidx.compose.ui.graphics.Color(0xFF888888),
                    )
                }
            }
        }
    }
}
