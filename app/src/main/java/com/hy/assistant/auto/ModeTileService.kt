package com.hy.assistant.auto

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.hy.assistant.HyApp
import com.hy.assistant.R
import com.hy.assistant.ReplyMode

/** Quick Settings tile: tap to switch Hy between Manual and Auto reply mode. */
class ModeTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val settings = HyApp.instance.settings
        settings.update { it.copy(replyMode = if (it.replyMode == ReplyMode.AUTO) ReplyMode.MANUAL else ReplyMode.AUTO) }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val auto = HyApp.instance.settings.current.replyMode == ReplyMode.AUTO
        tile.state = if (auto) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Alfrid auto-reply"
        tile.subtitle = if (auto) "Auto" else "Manual"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_hy)
        tile.updateTile()
    }
}
