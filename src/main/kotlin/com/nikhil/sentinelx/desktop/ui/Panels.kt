package com.nikhil.sentinelx.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nikhil.sentinelx.desktop.core.format.AccountEntity
import com.nikhil.sentinelx.desktop.core.format.ArtifactEntity
import com.nikhil.sentinelx.desktop.core.format.BankTxnEntity
import com.nikhil.sentinelx.desktop.core.format.CashBook
import com.nikhil.sentinelx.desktop.core.format.CashEntryEntity
import com.nikhil.sentinelx.desktop.core.format.ChronicleEntity
import com.nikhil.sentinelx.desktop.core.format.LoginEntity
import com.nikhil.sentinelx.desktop.core.format.ProphecyEntity
import com.nikhil.sentinelx.desktop.core.format.TransactionEntity
import com.nikhil.sentinelx.desktop.ui.components.LocalPanelScope
import com.nikhil.sentinelx.desktop.ui.components.PanelScope
import com.nikhil.sentinelx.desktop.ui.panes.AccountEditor
import com.nikhil.sentinelx.desktop.ui.panes.ArtifactEditor
import com.nikhil.sentinelx.desktop.ui.panes.BankTxnCreate
import com.nikhil.sentinelx.desktop.ui.panes.BillEditor
import com.nikhil.sentinelx.desktop.ui.panes.BankTxnEditor
import com.nikhil.sentinelx.desktop.ui.panes.CashEntryEditor
import com.nikhil.sentinelx.desktop.ui.panes.ChronicleEditor
import com.nikhil.sentinelx.desktop.ui.panes.LoginEditor
import com.nikhil.sentinelx.desktop.ui.panes.NoteEditor
import com.nikhil.sentinelx.desktop.ui.panes.StatementImportWizard
import com.nikhil.sentinelx.desktop.ui.panes.TransactionEditor
import kotlin.math.roundToInt

/**
 * Floating editors.
 *
 * Every add/edit form used to be a modal `Dialog`: it dimmed the app, took every
 * click, and only one could exist at a time. That is wrong for a desktop. Reading one
 * login while typing another, copying an amount out of yesterday's cash entry into
 * today's, checking a note while filling in a card — all of it needed the form closed
 * first, which meant losing what you had typed.
 *
 * So the forms are panels instead: dragged by their title bar, stacked, non-modal. The
 * pane behind them stays live — scroll it, search it, click a different record and open
 * that one too.
 *
 * The one thing panels must not allow is **two editors over the same record**. Both
 * would hold a snapshot taken when they opened, and whichever saved second would
 * silently undo the other. [PanelRequest.identity] is what prevents it: asking for a
 * record that is already open raises that panel rather than opening a second one. A
 * *new* record has no identity, so you can have as many blank forms as you like.
 */
sealed interface PanelRequest {

    /** Stable key for an existing record; null for a new one, which is never deduplicated. */
    val identity: String?

    data class Login(val existing: LoginEntity?, val prefillSite: String? = null) : PanelRequest {
        override val identity get() = existing?.let { "login:${it.id}" }
    }

    data class Card(val existing: ArtifactEntity?) : PanelRequest {
        override val identity get() = existing?.let { "card:${it.id}" }
    }

    data class Note(
        val existing: ProphecyEntity?,
        /** Folder a brand-new note starts filed in — set when "+" is pressed inside one. */
        val prefillFolder: String? = null
    ) : PanelRequest {
        override val identity get() = existing?.let { "note:${it.id}" }
    }

    data class Chronicle(val existing: ChronicleEntity?) : PanelRequest {
        override val identity get() = existing?.let { "chronicle:${it.id}" }
    }

    data class Account(val existing: AccountEntity?) : PanelRequest {
        override val identity get() = existing?.let { "account:${it.id}" }
    }

    data class Transaction(
        val existing: TransactionEntity?,
        val defaultAccountId: Long? = null
    ) : PanelRequest {
        override val identity get() = existing?.let { "tx:${it.id}" }
    }

    data class CashEntry(
        val existing: CashEntryEntity?,
        val date: Long,
        val slot: String = CashBook.SLOT_OTHER,
        val seedDenominations: Map<Int, Int> = emptyMap()
    ) : PanelRequest {
        override val identity get() = existing?.let { "cash:${it.id}" }
    }

    data class BankTxn(val existing: BankTxnEntity) : PanelRequest {
        override val identity get() = "bank:${existing.id}"
    }

    /**
     * The statement import wizard. A constant identity on purpose: there is
     * exactly one import in flight at a time, and clicking IMPORT again raises
     * the wizard mid-flow instead of opening a second one that would race the
     * first over the same book.
     */
    /** Add / edit a bill. Null = new. */
    data class Bill(val existing: com.nikhil.sentinelx.desktop.core.format.BillEntity?) : PanelRequest {
        override val identity get() = existing?.let { "bill:${it.id}" } ?: "bill:new"
    }

    /** A hand-typed bank entry. One at a time — the identity is constant. */
    data class BankTxnNew(val defaultBook: String?) : PanelRequest {
        override val identity get() = "bank:new"
    }

    data class StatementImport(val defaultBook: String?) : PanelRequest {
        override val identity get() = "statement-import"
    }
}

/**
 * One open panel.
 *
 * [offset] and [size] are `mutableStateOf` on the panel rather than fields of an
 * immutable list element on purpose: dragging then mutates one panel instead of
 * rebuilding the list sixty times a second, and because the offset is read inside a
 * layout lambda the drag never recomposes anything at all.
 */
class Panel internal constructor(
    val key: String,
    val request: PanelRequest,
    internal val cascade: Int
) {
    /** Null until the panel has been measured and placed. */
    internal var offset by mutableStateOf<Offset?>(null)
    internal var size by mutableStateOf(IntSize.Zero)

    // Resize lives on the panel and nowhere else, which is exactly the requested
    // behaviour: a panel is built fresh by `open`, so closing one and reopening it
    // brings back the editor's own default size. Nothing to persist, nothing to reset.
    internal var widthPx by mutableStateOf<Float?>(null)
    internal var contentHeightPx by mutableStateOf<Float?>(null)
}

/** Below these a panel stops being a form and starts being a sliver. */
private const val MIN_PANEL_WIDTH = 360f
private const val MIN_CONTENT_HEIGHT = 120f

class PanelHostState {

    var panels by mutableStateOf<List<Panel>>(emptyList())
        private set

    private var sequence = 0
    private var opened = 0

    /** Opens [request], or raises the panel already showing that record. */
    fun open(request: PanelRequest) {
        val id = request.identity
        if (id != null && panels.any { it.key == id }) {
            raise(id)
            return
        }
        val key = id ?: "new#${sequence++}"
        panels = panels + Panel(key, request, cascade = opened++ % 6)
    }

    fun close(key: String) {
        panels = panels.filterNot { it.key == key }
        // Nothing left open, so the next panel starts from the top of the cascade
        // again instead of appearing further down the screen every session.
        if (panels.isEmpty()) opened = 0
    }

    fun closeAll() {
        panels = emptyList()
        opened = 0
    }

    /** Last in the list is drawn last, so it is on top. */
    fun raise(key: String) {
        if (panels.lastOrNull()?.key == key) return
        val target = panels.firstOrNull { it.key == key } ?: return
        panels = panels.filterNot { it.key == key } + target
    }

    fun closeTop(): Boolean {
        val top = panels.lastOrNull() ?: return false
        close(top.key)
        return true
    }
}

/**
 * Renders every open panel above the pane content.
 *
 * Always composed, even with nothing open — it needs its own measured size before the
 * first panel can be centred, and an empty [Box] with no background and no pointer
 * modifier is invisible to hit testing, so the app underneath behaves exactly as if it
 * were not there.
 */
@Composable
fun PanelHost(state: AppState) {
    val host = state.panels

    // BoxWithConstraints, not fillMaxSize() + onSizeChanged. A parent's onSizeChanged
    // fires only once its children have been measured, so the first panel asked where
    // to place itself while the host still measured zero, gave up, and stayed invisible
    // — and since its own size never changed again, it was never asked a second time.
    // Constraints are known before the content composes at all.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hostSize = IntSize(constraints.maxWidth, constraints.maxHeight)

        host.panels.forEach { panel ->
            key(panel.key) {
                val top = host.panels.lastOrNull()?.key == panel.key
                // Built fresh each recomposition rather than remembered: it carries the
                // live resize dimensions, and a remembered copy would hold yesterday's.
                val scope = PanelScope(
                    focused = top,
                    width = panel.widthPx,
                    contentHeight = panel.contentHeightPx,
                    onDrag = { delta ->
                        val from = panel.offset ?: Offset.Zero
                        panel.offset = clampToHost(from + delta, hostSize, panel.size)
                    },
                    onResizeBegin = { width, contentHeight ->
                        if (panel.widthPx == null) panel.widthPx = width
                        if (panel.contentHeightPx == null) panel.contentHeightPx = contentHeight
                    },
                    onResize = { delta ->
                        val maxWidth = (hostSize.width - 32f).coerceAtLeast(MIN_PANEL_WIDTH)
                        val maxContent = (hostSize.height - 220f).coerceAtLeast(MIN_CONTENT_HEIGHT)
                        panel.widthPx = ((panel.widthPx ?: MIN_PANEL_WIDTH) + delta.x)
                            .coerceIn(MIN_PANEL_WIDTH, maxWidth)
                        panel.contentHeightPx = ((panel.contentHeightPx ?: MIN_CONTENT_HEIGHT) + delta.y)
                            .coerceIn(MIN_CONTENT_HEIGHT, maxContent)
                    }
                )

                Box(
                    Modifier
                        .offset {
                            panel.offset
                                ?.let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) }
                                ?: IntOffset.Zero
                        }
                        // Invisible for the one frame between being measured and being
                        // placed, rather than flashing in the top-left corner first.
                        .alpha(if (panel.offset == null) 0f else 1f)
                        .onSizeChanged { measured ->
                            panel.size = measured
                            if (panel.offset == null && hostSize.width > 0) {
                                panel.offset = initialOffset(hostSize, measured, panel.cascade)
                            }
                        }
                        // Two jobs. Raising on press is the obvious one. The subtle one
                        // is that owning a pointer node at all is what stops clicks
                        // falling through to the pane behind — a background is a draw
                        // modifier and would let them straight through.
                        .pointerInput(host) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type == PointerEventType.Press) host.raise(panel.key)
                                }
                            }
                        }
                ) {
                    CompositionLocalProvider(LocalPanelScope provides scope) {
                        PanelContent(state, panel) { host.close(panel.key) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelContent(state: AppState, panel: Panel, onClose: () -> Unit) {
    when (val request = panel.request) {
        is PanelRequest.Login ->
            LoginEditor(state, request.existing, request.prefillSite, onClose)

        is PanelRequest.Card ->
            ArtifactEditor(state, request.existing, onClose)

        is PanelRequest.Note ->
            NoteEditor(state, request.existing, request.prefillFolder, onClose)

        is PanelRequest.Chronicle ->
            ChronicleEditor(state, request.existing, onClose)

        is PanelRequest.Account ->
            AccountEditor(state, request.existing, onClose)

        is PanelRequest.Transaction ->
            TransactionEditor(state, request.existing, request.defaultAccountId, onClose)

        is PanelRequest.CashEntry ->
            CashEntryEditor(
                state = state,
                existing = request.existing,
                defaultDate = request.date,
                defaultSlot = request.slot,
                seedDenominations = request.seedDenominations,
                onClose = onClose
            )

        is PanelRequest.BankTxn ->
            BankTxnEditor(state, request.existing, onClose)

        is PanelRequest.BankTxnNew ->
            BankTxnCreate(state, request.defaultBook, onClose)

        is PanelRequest.Bill ->
            BillEditor(state, request.existing, onClose)

        is PanelRequest.StatementImport ->
            StatementImportWizard(state, request.defaultBook, onClose)
    }
}

/**
 * Centred near the top, then stepped so a stack does not hide itself.
 *
 * Centring on a *nominal* width rather than the panel's own is the point. Editors are
 * 560 to 720 wide, and centring each on its own width moved a wide panel left by more
 * than the cascade moved it right — so the cash entry form landed exactly on top of the
 * login form and swallowed it whole. Anchoring every panel to the same left edge means
 * the step is the only thing that moves them.
 */
private fun initialOffset(host: IntSize, panel: IntSize, cascade: Int): Offset {
    val nominalWidth = 600f
    val step = 48f * cascade
    return clampToHost(
        Offset(((host.width - nominalWidth) / 2f) + step, 56f + step),
        host,
        panel
    )
}

/**
 * Keeps a strip of the panel — and always its title bar — inside the window.
 *
 * A panel dragged fully off the edge cannot be dragged back, and closing it means
 * finding it first. Half a title bar is enough to grab.
 */
private fun clampToHost(offset: Offset, host: IntSize, panel: IntSize): Offset {
    if (host.width == 0 || panel.width == 0) return offset
    val keepVisible = 180f
    val minX = -(panel.width - keepVisible).coerceAtLeast(0f)
    val maxX = (host.width - keepVisible).coerceAtLeast(0f)
    val maxY = (host.height - 48f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(minX, maxX), offset.y.coerceIn(0f, maxY))
}
