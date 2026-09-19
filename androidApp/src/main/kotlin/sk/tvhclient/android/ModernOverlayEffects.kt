package sk.tvhclient.android

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * M663: „Viac" panel moderného režimu (telefón) — BackHandler + ModernMoreSheet so všetkými
 * akciami; zápisy do stavov PlayerUi cez settery. 1:1 s pôvodným blokom v PlayerUi.
 */
@Composable
internal fun PlayerMoreSheetHost(
    ctx: android.content.Context,
    pipSupported: Boolean,
    pipButton: Boolean,
    profileSwitch: Boolean,
    orientationLocked: Boolean,
    dvrActivity: PlayerActivity?,
    onEnterPip: () -> Unit,
    onOpenSleep: () -> Unit,
    onOrientationLockChange: (Boolean) -> Unit,
    setShowMoreSheet: (Boolean) -> Unit,
    setMenu: (String?) -> Unit,
    setShowInfo: (Boolean) -> Unit,
    setOrientationLocked: (Boolean) -> Unit
) {
    BackHandler { setShowMoreSheet(false) }
    val lockVis = pipSupported && OrientationPref.get(ctx) == OrientationPref.AUTO
    ModernMoreSheet(
        lockVisible = lockVis,
        orientationLocked = orientationLocked,
        pipVisible = pipButton,
        profileVisible = profileSwitch,
        onProfile = { setShowMoreSheet(false); setMenu("profile") },
        onPip = { setShowMoreSheet(false); onEnterPip() },
        onSubs = { setShowMoreSheet(false); setMenu("spu") },
        // M490: rovnaky stav aj akcia ako klasicky bar a TV overlay
        recordVisible = dvrActivity?.dvrRecordVisible() == true,
        recordIsCancel = dvrActivity?.dvrExistingState?.value != null,
        onRecord = {
            setShowMoreSheet(false)
            dvrActivity?.toggleRecordCurrent()
        },
        teletextVisible = dvrActivity?.teletextVisible() == true,   // M559
        onTeletext = { setShowMoreSheet(false); dvrActivity?.openTeletext() },
        onSleep = { setShowMoreSheet(false); onOpenSleep() },
        onLockToggle = {
            val locked = !orientationLocked
            setOrientationLocked(locked)
            onOrientationLockChange(locked)
        },
        onInfo = { setShowMoreSheet(false); setShowInfo(true) },
        onDismiss = { setShowMoreSheet(false) },
    )
}

/**
 * M663: efekty moderného TV overlayu — exkluzivita (zatvorí ostatné prekryvy), auto-hide po 6 s
 * a vykonanie akcie z lišty (signál z Activity key handlera). Kľúče LaunchedEffect zhodné.
 */
@Composable
internal fun ModernOverlayEffects(
    modernOvVisible: Boolean,
    modernOvPoke: Int,
    modernOvExec: Int,
    modernOvExecId: String,
    modernOvCard: Int,
    onModernOvDismiss: () -> Unit,
    onSelectChannel: (Int) -> Unit,
    onOpenSleep: () -> Unit,
    onOpenEpg: () -> Unit,
    closeOverlays: () -> Unit,
    setMenu: (String?) -> Unit,
    setShowInfo: (Boolean) -> Unit
) {
    if (modernOvVisible) {
        LaunchedEffect(Unit) { closeOverlays() }
    }
    LaunchedEffect(modernOvVisible, modernOvPoke) {
        if (modernOvVisible) {
            kotlinx.coroutines.delay(6000)
            onModernOvDismiss()
        }
    }
    LaunchedEffect(modernOvExec) {
        if (modernOvExec > 0) when (modernOvExecId) {
            "card" -> onSelectChannel(modernOvCard)
            "audio" -> setMenu("audio")
            "subs" -> setMenu("spu")
            "sleep" -> onOpenSleep()
            "epg" -> onOpenEpg()
            "info" -> setShowInfo(true)
        }
    }
}
