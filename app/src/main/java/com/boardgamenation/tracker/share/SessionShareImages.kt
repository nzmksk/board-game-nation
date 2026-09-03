package com.boardgamenation.tracker.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.boardgamenation.tracker.BuildConfig
import com.boardgamenation.tracker.core.time.DateUtils
import com.boardgamenation.tracker.di.IoDispatcher
import com.boardgamenation.tracker.domain.share.ShareCard
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Turns a card into a file another app can read.
 *
 * The picture is a throwaway: it exists to be handed to a chooser and is worthless the
 * moment the user has sent it, so it lives in the cache under a directory of its own and
 * the previous one is deleted before the next is written. Anything the user wants to
 * keep, the app they shared it to has already saved.
 *
 * The directory is its own for the sake of the grant. `FileProvider` exposes whatever
 * its paths file names, and naming the whole cache would put the BGG image cache one
 * guessed filename away from any app that received a share.
 */
@Singleton
class SessionShareImages @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val renderer: ShareCardRenderer,
    @param:IoDispatcher private val io: CoroutineDispatcher
) {

    /** Renders the card, writes it, and returns the uri to grant. */
    suspend fun write(card: ShareCard): Uri = withContext(io) {
        val directory = File(context.cacheDir, DIRECTORY)
        directory.mkdirs()
        directory.listFiles()?.forEach { it.delete() }

        val file = File(directory, fileName(card))
        val bitmap = renderer.render(card)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }

        FileProvider.getUriForFile(context, AUTHORITY, file)
    }

    /**
     * What the file is called wherever it lands. A share that arrives as
     * `wingspan-2026-09-02.png` is findable in a downloads folder a week later;
     * `image.png` is not.
     */
    private fun fileName(card: ShareCard): String {
        val title = card.gameTitle
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
            .take(48)
            .ifEmpty { "play" }
        return "$title-${DateUtils.toIso(card.playedOn)}.png".lowercase()
    }

    private companion object {
        val AUTHORITY = "${BuildConfig.APPLICATION_ID}.fileprovider"
        const val DIRECTORY = "share"
    }
}

/**
 * The system share sheet, pointed at a rendered card.
 *
 * Deliberately image and nothing else. Adding EXTRA_TEXT would give some targets a
 * choice between the picture and the sentence, and the ones that choose the sentence
 * drop the very thing being shared -- everything worth saying is drawn on the card
 * anyway.
 *
 * The clip data is not decoration: it is what carries the read grant to whichever app
 * the user picks, since the chooser itself is not the recipient.
 */
fun shareImageChooser(uri: Uri, label: String, chooserTitle: String): Intent {
    val send = Intent(Intent.ACTION_SEND)
        .setType(MIME_TYPE)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri(label, uri) }
    return Intent.createChooser(send, chooserTitle)
}

private const val MIME_TYPE = "image/png"
