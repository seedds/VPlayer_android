package com.seedds.vplayer.data.fs

import com.seedds.vplayer.data.model.LibraryItem
import com.seedds.vplayer.data.model.LibraryKind
import java.text.Collator

/**
 * Orders names the way a person reads them: `ep2` before `ep10`, case and
 * accents ignored. This is the JavaScript
 * `localeCompare(other, undefined, { numeric: true, sensitivity: 'base' })`
 * the React Native build sorted with, reimplemented so it behaves identically
 * on the JVM and on Android.
 *
 * Digit runs are compared by value and everything else through a primary
 * strength [Collator], which is what makes it blind to case and accents.
 */
object NaturalOrderComparator : Comparator<String> {

    private val collator: Collator = Collator.getInstance().apply {
        strength = Collator.PRIMARY
    }

    override fun compare(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0

        while (leftIndex < left.length && rightIndex < right.length) {
            val leftDigits = left[leftIndex].isDigit()
            val rightDigits = right[rightIndex].isDigit()

            val leftEnd = runEnd(left, leftIndex, leftDigits)
            val rightEnd = runEnd(right, rightIndex, rightDigits)
            val leftChunk = left.substring(leftIndex, leftEnd)
            val rightChunk = right.substring(rightIndex, rightEnd)

            val result = if (leftDigits && rightDigits) {
                compareNumeric(leftChunk, rightChunk)
            } else {
                collator.compare(leftChunk, rightChunk)
            }
            if (result != 0) return result

            leftIndex = leftEnd
            rightIndex = rightEnd
        }

        return (left.length - leftIndex).compareTo(right.length - rightIndex)
    }

    /** End index of the digit or non-digit run starting at [start]. */
    private fun runEnd(value: String, start: Int, digits: Boolean): Int {
        var index = start
        while (index < value.length && value[index].isDigit() == digits) index++
        return index
    }

    /**
     * Compares two digit runs by value without parsing, so arbitrarily long
     * runs are safe. Leading zeros only break a tie, keeping `01` next to `1`
     * but deterministically ordered.
     */
    private fun compareNumeric(left: String, right: String): Int {
        val leftTrimmed = left.trimStart('0')
        val rightTrimmed = right.trimStart('0')
        if (leftTrimmed.length != rightTrimmed.length) {
            return leftTrimmed.length.compareTo(rightTrimmed.length)
        }
        val digits = leftTrimmed.compareTo(rightTrimmed)
        if (digits != 0) return digits
        return left.length.compareTo(right.length)
    }
}

/**
 * The library's display order: folders first, then names in natural order.
 * Fixed by design; the app has no sort UI.
 */
val LibraryItemComparator: Comparator<LibraryItem> = Comparator { left, right ->
    val leftIsFolder = left.kind == LibraryKind.Folder
    val rightIsFolder = right.kind == LibraryKind.Folder
    when {
        leftIsFolder && !rightIsFolder -> -1
        !leftIsFolder && rightIsFolder -> 1
        else -> NaturalOrderComparator.compare(left.name, right.name)
    }
}
