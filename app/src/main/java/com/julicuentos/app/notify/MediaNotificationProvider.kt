package com.julicuentos.app.notify

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.julicuentos.app.R
import com.julicuentos.app.catalog.Story
import com.julicuentos.app.catalog.StoryRepository
import com.julicuentos.app.media.BitmapDecoder

// Custom media notification provider (design D2; NOT the DefaultMediaNotificationProvider).

class MediaNotificationProvider(private val context: Context) : MediaNotification.Provider {

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_ARTWORK_PX = 256
        const val ACTION_SKIP_BACK_15 = "jc.action.skip_back_15"
        const val ACTION_SKIP_FWD_15 = "jc.action.skip_fwd_15"
        const val ACTION_NEXT = "jc.action.next"
    }

    private var artworkKey: String? = null
    private var artworkBitmap: android.graphics.Bitmap? = null

    override fun createNotification(mediaSession: MediaSession, customLayout: ImmutableList<CommandButton>, actionFactory: MediaNotification.ActionFactory, onNotificationChangedCallback: MediaNotification.Provider.Callback): MediaNotification {

        ensureChannel()
        val player = mediaSession.getPlayer()
        val storyId = player.currentMediaItem?.mediaId
        val story = storyId?.let { StoryRepository.get(context).getById(it) }
        val itemTitle = player.currentMediaItem?.mediaMetadata?.title?.toString()
        val contentTitle = itemTitle ?: story?.titulo ?: context.getString(R.string.app_name)

        val isPlaying = player.playWhenReady
        val playRes = if (isPlaying)R.drawable.ic_act_pause else R.drawable.ic_act_play

        val action1 = actionFactory.createMediaAction(mediaSession, iconCompat(playRes), context.getString(R.string.notif_play_pause), Player.COMMAND_PLAY_PAUSE)
        val action2 = actionFactory.createCustomAction(mediaSession, iconCompat(R.drawable.ic_act_skip_back), context.getString(R.string.notif_skip_back_15), ACTION_SKIP_BACK_15, Bundle())
        val action3 = actionFactory.createCustomAction(mediaSession, iconCompat(R.drawable.ic_act_skip_fwd), context.getString(R.string.notif_skip_fwd_15), ACTION_SKIP_FWD_15, Bundle())
        val action4 = actionFactory.createCustomAction(mediaSession, iconCompat(R.drawable.ic_act_next), context.getString(R.string.notif_next), ACTION_NEXT, Bundle())
        val actions = listOf(action1, action2, action3, action4)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
        builder.setSmallIcon(R.drawable.ic_stat_note)
        builder.setContentTitle(contentTitle)
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        builder.setOngoing(isPlaying)
        builder.setCategory(NotificationCompat.CATEGORY_TRANSPORT)
        builder.setPriority(NotificationCompat.PRIORITY_LOW)

        val style = androidx.media.app.NotificationCompat.MediaStyle(builder)
            style.setShowActionsInCompactView(0, 1, 2)
        mediaSession.getSessionCompatToken()?.let { style.setMediaSession(it) }


        for (entry in actions.withIndex()) builder.addAction(entry.value)

        updateArtwork(story)
        story?.let { s ->
            val bmp = artworkBitmap
            if (bmp != null) builder.setLargeIcon(bmp)
        }
        builder.setStyle(style)
            return MediaNotification(NOTIFICATION_ID, builder.build())

    }
    override fun handleCustomCommand(mediaSession: MediaSession, action: String, extras: Bundle): Boolean = false

    private fun iconCompat(resId: Int): IconCompat = IconCompat.createWithResource(context, resId)

    private fun updateArtwork(story: Story?) {
        val key = story?.id ?: return
        if (key == artworkKey) return
        artworkKey = key
        artworkBitmap = BitmapDecoder.decodeSampled(context, story.cover, NOTIFICATION_ARTWORK_PX)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val nm = context.getSystemService(NotificationManager::class.java) ?: return
                val channel = NotificationChannel(CHANNEL_ID,context.getString(R.string.notification_channel_name),NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
    }
}
