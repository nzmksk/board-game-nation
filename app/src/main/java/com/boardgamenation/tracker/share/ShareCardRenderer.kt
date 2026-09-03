package com.boardgamenation.tracker.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.core.time.DurationFormat
import com.boardgamenation.tracker.domain.share.ShareCard
import com.boardgamenation.tracker.domain.share.ShareResult
import com.boardgamenation.tracker.domain.share.ShareStanding
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a [ShareCard] as a picture, at the proportions a phone shows full-screen.
 *
 * ### Why a canvas and not a composable
 *
 * The card is never on screen. Rendering a composable to a bitmap means attaching it to
 * a window and waiting for a frame, which drags in a lifecycle the ViewModel does not
 * have and produces something whose size depends on the device it was drawn on. Drawing
 * straight onto a fixed 1080x1920 bitmap is a pure function of the card instead: the
 * same play produces the same image on every phone, off the main thread, with no view
 * tree involved.
 *
 * ### Why it ignores the app's theme
 *
 * Everything else in the app follows the wallpaper through dynamic colour. This does
 * not, and neither does it follow light or dark mode. The image outlives the device it
 * was made on -- it lands in a group chat, on a story, in somebody's camera roll -- so
 * its colours are the app's own and fixed. A card whose look depended on the sender's
 * phone would make the same result arrive looking like a different app each time.
 *
 * Sizes are raw pixels rather than dp for the same reason: the canvas is a constant, so
 * a density would only move the layout around for no gain.
 */
@Singleton
class ShareCardRenderer @Inject constructor(@param:ApplicationContext private val context: Context) {

    fun render(card: ShareCard): Bitmap {
        val bitmap = createBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(bitmap)

        drawBackground(canvas)
        val headerBottom = drawHeader(canvas, card)
        val footerTop = drawFooter(canvas)
        val seatingTop = drawSeating(canvas, card, footerTop)
        drawStandings(canvas, card, top = headerBottom, bottom = seatingTop)

        return bitmap
    }

    // -- Background ---------------------------------------------------------------

    private fun drawBackground(canvas: Canvas) {
        val felt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                HEIGHT.toFloat(),
                BACKGROUND_TOP,
                BACKGROUND_BOTTOM,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), felt)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), 12f, fill(GOLD))
    }

    // -- Header -------------------------------------------------------------------

    /** Draws the date, title, figures and badges. Returns the y the standings start at. */
    private fun drawHeader(canvas: Canvas, card: ShareCard): Float {
        var y = 152f

        val date = card.playedOn.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        val datePaint = text(size = 38f, color = MUTED, bold = true).apply {
            letterSpacing = 0.18f
        }
        canvas.drawText(date.uppercase(), MARGIN, y + datePaint.textSize, datePaint)
        y += datePaint.textSize + 40f

        val titlePaint = text(size = 96f, color = INK, bold = true)
        y += wrapped(
            canvas = canvas,
            value = card.gameTitle,
            paint = titlePaint,
            x = MARGIN,
            top = y,
            maxLines = 3
        )
        y += 28f

        val figures = listOf(
            DurationFormat.minutes(card.durationMinutes),
            context.resources.getQuantityString(
                R.plurals.unit_players,
                card.playerCount,
                card.playerCount
            )
        ).joinToString(SEPARATOR)
        val figuresPaint = text(size = 42f, color = MUTED)
        canvas.drawText(figures, MARGIN, y + figuresPaint.textSize, figuresPaint)
        y += figuresPaint.textSize + 24f

        val badges = listOfNotNull(
            card.mode,
            card.endReason,
            context.getString(R.string.session_incomplete).takeIf { card.isIncomplete },
            context.getString(R.string.session_teaching).takeIf { card.isTeachingGame }
        )
        if (badges.isNotEmpty()) y += drawBadges(canvas, badges, y) + 20f

        headline(card)?.let { headline ->
            y += 20f
            y += wrapped(
                canvas = canvas,
                value = headline,
                paint = text(size = 64f, color = GOLD, bold = true),
                x = MARGIN,
                top = y,
                maxLines = 2
            )
        }

        return y + 56f
    }

    /**
     * The one line somebody reads before deciding whether to open the picture. Left off
     * entirely when the play has no recorded outcome, rather than filled with a guess.
     */
    private fun headline(card: ShareCard): String? = when (card.result) {
        ShareResult.COOP_WIN -> context.getString(R.string.session_coop_win)

        ShareResult.COOP_LOSS -> context.getString(R.string.session_coop_loss)

        ShareResult.TEAMS ->
            card.winningTeam
                ?.let { context.getString(R.string.session_team_won, it) }

        ShareResult.RANKED ->
            card.winners
                .takeIf { it.isNotEmpty() }
                ?.let { context.getString(R.string.session_winner, it.joinToString(", ")) }

        ShareResult.UNRESOLVED -> null
    }

    /** Free-text notes about the play, as pills that wrap onto a second row. */
    private fun drawBadges(canvas: Canvas, badges: List<String>, top: Float): Float {
        val paint = text(size = 34f, color = INK)
        val height = 68f
        val padding = 30f
        val gap = 14f
        var x = MARGIN
        var y = top

        badges.forEach { badge ->
            val label = ellipsised(badge, paint, CONTENT_WIDTH - padding * 2)
            val width = paint.measureText(label) + padding * 2
            if (x > MARGIN && x + width > WIDTH - MARGIN) {
                x = MARGIN
                y += height + gap
            }
            canvas.drawRoundRect(
                RectF(x, y, x + width, y + height),
                height / 2,
                height / 2,
                fill(BADGE_FILL)
            )
            canvas.drawText(label, x + padding, baselineIn(y, y + height, paint), paint)
            x += width + gap
        }

        return y - top + height
    }

    // -- Standings ----------------------------------------------------------------

    /**
     * Fills whatever space the header and the footer left. Rows stretch to use it and
     * stop growing at a readable maximum, so a two-player game does not end up with two
     * enormous bars; once they stop growing the block is centred in what is left, so a
     * short table sits in the middle of the card rather than hanging off the header.
     *
     * When there are more players than fit, the last slot says how many are missing
     * rather than the card silently dropping them.
     */
    private fun drawStandings(canvas: Canvas, card: ShareCard, top: Float, bottom: Float) {
        val available = bottom - top
        if (available < MIN_ROW_HEIGHT || card.standings.isEmpty()) return

        val overflowing = card.standings.size > rowsWithin(available)
        // The note that says who was left out needs a line, not a row, and taking a
        // whole row's worth for it would push another player off the card to say so.
        val forRows = if (overflowing) available - OVERFLOW_HEIGHT - ROW_GAP else available
        val visible = min(card.standings.size, max(1, rowsWithin(forRows)))
        val height = min(MAX_ROW_HEIGHT, (forRows + ROW_GAP) / visible - ROW_GAP)
        val block = visible * (height + ROW_GAP) - ROW_GAP +
            if (overflowing) ROW_GAP + OVERFLOW_HEIGHT else 0f
        val start = top + max(0f, (available - block) / 2)

        card.standings.take(visible).forEachIndexed { index, standing ->
            drawStanding(canvas, standing, start + index * (height + ROW_GAP), height)
        }

        if (overflowing) {
            val remaining = card.standings.size - visible
            val paint = text(size = 38f, color = MUTED)
            val y = start + visible * (height + ROW_GAP)
            canvas.drawText(
                context.resources.getQuantityString(
                    R.plurals.share_card_more_players,
                    remaining,
                    remaining
                ),
                MARGIN + 32f,
                baselineIn(y, y + OVERFLOW_HEIGHT, paint),
                paint
            )
        }
    }

    private fun rowsWithin(height: Float): Int = ((height + ROW_GAP) / (MIN_ROW_HEIGHT + ROW_GAP)).toInt()

    private fun drawStanding(canvas: Canvas, standing: ShareStanding, top: Float, height: Float) {
        val bottom = top + height
        val row = RectF(MARGIN, top, WIDTH - MARGIN, bottom)
        val radius = 32f

        val background = if (standing.isWinner) WINNER_FILL else ROW_FILL
        canvas.drawRoundRect(row, radius, radius, fill(background))
        if (standing.isWinner) {
            canvas.drawRoundRect(row, radius, radius, stroke(GOLD, 4f))
        }

        // The badge holds the placement when the play was ranked. A co-op and a team
        // game have no order inside them, so it falls back to the player's initial --
        // an empty disc down the side of every row looks like something failed to load.
        val badgeRadius = (height * 0.29f).coerceIn(30f, 46f)
        val badgeCentre = MARGIN + 32f + badgeRadius
        canvas.drawCircle(
            badgeCentre,
            (top + bottom) / 2,
            badgeRadius,
            fill(if (standing.isWinner) GOLD else BADGE_FILL)
        )
        val badge = standing.rank?.toString() ?: initialOf(standing.name)
        if (badge.isNotEmpty()) {
            val paint = text(
                size = badgeRadius * 1.05f,
                color = if (standing.isWinner) BACKGROUND_BOTTOM else INK,
                bold = true
            ).apply { textAlign = Paint.Align.CENTER }
            canvas.drawText(badge, badgeCentre, baselineIn(top, bottom, paint), paint)
        }

        val scorePaint = text(
            size = (height * 0.36f).coerceIn(38f, 56f),
            color = if (standing.isWinner) GOLD else INK,
            bold = true
        ).apply { textAlign = Paint.Align.RIGHT }
        val scoreRight = WIDTH - MARGIN - 40f
        val scoreWidth = standing.scoreText
            ?.let { scorePaint.measureText(it) + 32f }
            ?: 0f
        standing.scoreText?.let {
            canvas.drawText(it, scoreRight, baselineIn(top, bottom, scorePaint), scorePaint)
        }

        // The side and the faction are the same kind of fact -- what this player was
        // rather than what they scored -- so they share one line under the name.
        val detail = listOfNotNull(standing.team, standing.faction).joinToString(SEPARATOR)
        val nameX = badgeCentre + badgeRadius + 30f
        // Type scales with the row so a big table stays legible as its rows compress,
        // rather than the text keeping its size and colliding with the row's edges.
        val namePaint = text(size = (height * 0.32f).coerceIn(34f, 48f), color = INK, bold = true)

        // Somebody's first play of the game gets a tag next to their name. It is
        // measured before the name is drawn rather than squeezed in afterwards, so a
        // long name is ellipsised down to the room actually left instead of the tag
        // landing on top of it or running under the score.
        val tag = context.getString(R.string.share_card_first_timer)
            .takeIf { standing.isNewPlayer }
        val tagPaint = text(size = (height * 0.2f).coerceIn(22f, 30f), color = INK, bold = true)
            .apply { letterSpacing = 0.08f }
        // A gap either side: one to part the tag from the name, and one so a name long
        // enough to fill the row cannot push the tag up against the score.
        val tagWidth = tag?.let { tagPaint.measureText(it) + TAG_PADDING * 2 + TAG_GAP * 2 } ?: 0f

        val nameWidth = scoreRight - scoreWidth - nameX - tagWidth
        val name = ellipsised(standing.name, namePaint, nameWidth)
        // With a side or a faction under it the name sits in the row's top half;
        // on its own it is centred.
        val nameBaseline =
            if (detail.isEmpty()) baselineIn(top, bottom, namePaint) else top + height * 0.44f
        canvas.drawText(name, nameX, nameBaseline, namePaint)

        if (detail.isNotEmpty()) {
            val detailPaint = text(size = (height * 0.23f).coerceIn(26f, 34f), color = MUTED)
            canvas.drawText(
                ellipsised(detail, detailPaint, nameWidth),
                nameX,
                top + height * 0.79f,
                detailPaint
            )
        }

        tag?.let {
            drawTag(
                canvas = canvas,
                label = it,
                paint = tagPaint,
                x = nameX + namePaint.measureText(name) + TAG_GAP,
                // Centred on the name, not on the row: a row-centred tag would drift
                // away from the word it belongs to as soon as a faction appeared below.
                centreY = nameBaseline + (namePaint.descent() + namePaint.ascent()) / 2
            )
        }
    }

    /**
     * The pill beside a name.
     *
     * Outlined as well as filled because it sits on a row that is already a lightened
     * panel: the badges under the title get away with a fill alone against the dark
     * background, but the same fill on a row is a few percent of white on a few percent
     * of white and disappears. The outline is [MUTED] rather than [GOLD] -- the accent
     * is what marks a winner, and a tag borrowing it would announce the wrong thing.
     */
    private fun drawTag(canvas: Canvas, label: String, paint: TextPaint, x: Float, centreY: Float) {
        val height = paint.textSize + 22f
        val top = centreY - height / 2
        val width = paint.measureText(label) + TAG_PADDING * 2
        val pill = RectF(x, top, x + width, top + height)
        canvas.drawRoundRect(pill, height / 2, height / 2, fill(BADGE_FILL))
        canvas.drawRoundRect(pill, height / 2, height / 2, stroke(MUTED, 2.5f))
        canvas.drawText(label, x + TAG_PADDING, baselineIn(top, top + height, paint), paint)
    }

    /**
     * The letter on an unranked badge. Taken by code point rather than by character, so
     * a name starting with an emoji or a character outside the basic plane does not come
     * back as half of one.
     */
    private fun initialOf(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ""
        val length = if (trimmed.length > 1 && trimmed[0].isHighSurrogate()) 2 else 1
        return trimmed.substring(0, length).uppercase()
    }

    // -- Seating and footer -------------------------------------------------------

    /**
     * The order the table played in, drawn upward from the footer. Returns its own top
     * so the standings know where to stop.
     *
     * A partial order is the ordinary case -- very often only the starting player was
     * written down -- and it gets the sentence the rest of the app uses for it rather
     * than a one-name list.
     */
    private fun drawSeating(canvas: Canvas, card: ShareCard, footerTop: Float): Float {
        if (card.turnOrder.isEmpty()) return footerTop - 40f

        // "Aina went first" is already a sentence and says what the heading would have.
        val onlyTheStarter = card.turnOrder.size == 1
        val seats = if (onlyTheStarter) {
            context.getString(R.string.session_first_player, card.turnOrder.first())
        } else {
            card.turnOrder.mapIndexed { index, name ->
                context.getString(R.string.session_edit_turn_order_seat, index + 1, name)
            }.joinToString(SEPARATOR)
        }

        val labelPaint = text(size = 32f, color = MUTED, bold = true).apply { letterSpacing = 0.16f }
        val labelHeight = if (onlyTheStarter) 0f else labelPaint.textSize + 16f
        val layout = layout(seats, text(size = 38f, color = INK), maxLines = 2)

        val top = footerTop - 48f - layout.height - labelHeight
        if (!onlyTheStarter) {
            canvas.drawText(
                context.getString(R.string.session_edit_turn_order).uppercase(),
                MARGIN,
                top + labelPaint.textSize,
                labelPaint
            )
        }
        canvas.withTranslation(MARGIN, top + labelHeight) { layout.draw(this) }

        return top - 40f
    }

    /** The watermark. Returns its top, which is the floor for everything above it. */
    private fun drawFooter(canvas: Canvas): Float {
        val paint = text(size = 44f, color = INK, bold = true).apply {
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.08f
        }
        val baseline = HEIGHT - 104f
        val ruleY = baseline - paint.textSize - 44f

        canvas.drawRoundRect(
            RectF(WIDTH / 2f - 54f, ruleY, WIDTH / 2f + 54f, ruleY + 8f),
            4f,
            4f,
            fill(GOLD)
        )
        canvas.drawText(context.getString(R.string.app_name), WIDTH / 2f, baseline, paint)

        return ruleY - 56f
    }

    // -- Drawing helpers ----------------------------------------------------------

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextPaint(
        Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG
    ).apply {
        this.color = color
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.SANS_SERIF
    }

    /** The baseline that centres a single line of text between two edges. */
    private fun baselineIn(top: Float, bottom: Float, paint: Paint): Float = (top + bottom) / 2 - (paint.descent() + paint.ascent()) / 2

    private fun ellipsised(value: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(value, paint, max(0f, width), TextUtils.TruncateAt.END).toString()

    private fun layout(value: String, paint: TextPaint, maxLines: Int): StaticLayout = StaticLayout.Builder
        .obtain(value, 0, value.length, paint, CONTENT_WIDTH.toInt())
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(0f, 0.96f)
        .setIncludePad(false)
        .setMaxLines(maxLines)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()

    /** Draws wrapping text and reports the height it took, for the caller's cursor. */
    private fun wrapped(canvas: Canvas, value: String, paint: TextPaint, x: Float, top: Float, maxLines: Int): Float {
        val layout = layout(value, paint, maxLines)
        canvas.withTranslation(x, top) { layout.draw(this) }
        return layout.height.toFloat()
    }

    companion object {
        /**
         * 9:16, which is what a story and a chat preview are cropped to. Fixed rather
         * than derived from the screen so every phone produces the same picture.
         */
        const val WIDTH = 1080
        const val HEIGHT = 1920

        private const val MARGIN = 84f
        private const val CONTENT_WIDTH = WIDTH - MARGIN * 2

        private const val ROW_GAP = 16f
        private const val MIN_ROW_HEIGHT = 88f
        private const val MAX_ROW_HEIGHT = 172f
        private const val OVERFLOW_HEIGHT = 52f

        /** The tag beside a name: the space inside its pill, and before it. */
        private const val TAG_PADDING = 22f
        private const val TAG_GAP = 18f

        /** The same separator the session list joins a result and its mode with. */
        private const val SEPARATOR = " · "

        private const val BACKGROUND_TOP = 0xFF0E3A28.toInt()
        private const val BACKGROUND_BOTTOM = 0xFF061410.toInt()
        private const val INK = 0xFFF3F6F1.toInt()
        private const val MUTED = 0xFF9FB3A7.toInt()
        private const val GOLD = 0xFFF7BD48.toInt()
        private const val ROW_FILL = 0x14FFFFFF
        private const val WINNER_FILL = 0x2EF7BD48
        private const val BADGE_FILL = 0x1FFFFFFF
    }
}
