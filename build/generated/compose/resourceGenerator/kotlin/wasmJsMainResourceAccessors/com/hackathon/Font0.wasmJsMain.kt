@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)

package com.hackathon

import kotlin.OptIn
import org.jetbrains.compose.resources.FontResource

private object WasmJsMainFont0 {
  public val NotoSansMono_Regular: FontResource by 
      lazy { init_NotoSansMono_Regular() }
}

internal val Res.font.NotoSansMono_Regular: FontResource
  get() = WasmJsMainFont0.NotoSansMono_Regular

private fun init_NotoSansMono_Regular(): FontResource =
    org.jetbrains.compose.resources.FontResource(
  "font:NotoSansMono_Regular",
    setOf(
      org.jetbrains.compose.resources.ResourceItem(setOf(),
    "font/NotoSansMono_Regular.ttf", -1, -1),
    )
)
