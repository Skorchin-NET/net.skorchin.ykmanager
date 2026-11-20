package net.skorchin.ykmanager.ui

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter

/**
 * Wraps a WebView print adapter and replaces the job's margins with [margins] on every layout
 * pass. The WebView adapter lays out the PDF inside PrintAttributes.minMargins and ignores
 * CSS @page margins, and the "Save as PDF" printer reports zero margins, so without this the
 * report prints edge to edge.
 */
internal class MarginPrintAdapter(
    private val delegate: PrintDocumentAdapter,
    private val margins: PrintAttributes.Margins,
) : PrintDocumentAdapter() {

    override fun onStart() = delegate.onStart()

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) = delegate.onLayout(oldAttributes?.withMargins(), newAttributes.withMargins(), cancellationSignal, callback, extras)

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor?,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback?,
    ) = delegate.onWrite(pages, destination, cancellationSignal, callback)

    override fun onFinish() = delegate.onFinish()

    private fun PrintAttributes.withMargins(): PrintAttributes {
        val b = PrintAttributes.Builder().setMinMargins(margins)
        mediaSize?.let { b.setMediaSize(it) }
        resolution?.let { b.setResolution(it) }
        if (colorMode != 0) b.setColorMode(colorMode)
        if (duplexMode != 0) b.setDuplexMode(duplexMode)
        return b.build()
    }
}
