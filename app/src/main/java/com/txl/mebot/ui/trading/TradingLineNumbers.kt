package com.txl.mebot.ui.trading

import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

internal val TradingLineNumbers = VisualTransformation { original ->
    val originalToNumbered = IntArray(original.length + 1)
    val numberedToOriginal = mutableListOf(0)
    val numbered = buildAnnotatedString {
        var item = 0
        for (offset in 0..original.length) {
            // A line start is the editable position after its generated number.
            if (offset == 0 || original[offset - 1] == '\n') {
                val prefix = "${++item}. "
                append(prefix)
                repeat(prefix.length) { numberedToOriginal.add(offset) }
            }
            originalToNumbered[offset] = length
            if (offset < original.length) {
                append(original[offset])
                numberedToOriginal.add(offset + 1)
            }
        }
    }
    TransformedText(numbered, object : OffsetMapping {
        override fun originalToTransformed(offset: Int): Int = originalToNumbered[offset]

        override fun transformedToOriginal(offset: Int): Int = numberedToOriginal[offset]
    })
}
