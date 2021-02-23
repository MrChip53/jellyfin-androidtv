package org.jellyfin.androidtv.data.service

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.util.Size
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.annotation.MainThread
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.window.WindowManager
import com.bumptech.glide.Glide
import kotlinx.coroutines.*
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.apiclient.interaction.ApiClient
import org.jellyfin.apiclient.model.dto.BaseItemDto
import org.jellyfin.apiclient.model.dto.ImageOptions
import org.jellyfin.apiclient.model.entities.ImageType

class BackgroundService(
	private val context: Context,
	private val apiClient: ApiClient,
	private val userPreferences: UserPreferences
) {
	companion object {
		const val TRANSITION_DURATION = 400L // 0.4 seconds
		const val SLIDESHOW_DURATION = 10000L // 10 seconds
		const val UPDATE_INTERVAL = 500L // 0.5 seconds
	}

	// Async
	private val scope = MainScope()
	private var loadBackgroundsJob: Job? = null
	private var updateBackgroundTimerJob: Job? = null
	private var lastBackgroundUpdate = 0L

	// All background drawables currently showing
	private val backgrounds = mutableListOf<Drawable>()

	// Current background index
	private var currentIndex = 0

	// Prefered display size, set when calling [attach].
	private var windowSize = Size(0, 0)
	private var windowBackground: Drawable = ColorDrawable(Color.BLACK)

	// Background layers
	private val backgroundDrawable = ContextCompat.getDrawable(context, R.drawable.layer_background) as LayerDrawable
	private val staticBackgroundLayer = backgroundDrawable.findIndexByLayerId(R.id.background_static)
	private val currentBackgroundLayer = backgroundDrawable.findIndexByLayerId(R.id.background_current)
	private val nextBackgroundLayer = backgroundDrawable.findIndexByLayerId(R.id.background_next)

	// Filter to darken backgrounds
	private val colorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
		context.getColor(R.color.background_filter),
		BlendModeCompat.SRC_ATOP
	)

	// Animation
	@Suppress("MagicNumber")
	private val backgroundAnimator = ValueAnimator.ofInt(0, 255).apply {
		interpolator = AccelerateDecelerateInterpolator()
		duration = TRANSITION_DURATION

		addUpdateListener { animation ->
			// Set alpha
			val value = animation.animatedValue as Int
			backgroundDrawable.getDrawable(nextBackgroundLayer).alpha = value
			backgroundDrawable.invalidateSelf()
		}

		doOnEnd {
			// Set next as current and clear next
			val drawable = backgroundDrawable.getDrawable(nextBackgroundLayer)
			backgroundDrawable.setDrawable(currentBackgroundLayer, drawable)
			backgroundDrawable.setDrawable(nextBackgroundLayer, ColorDrawable(Color.TRANSPARENT))
			backgroundDrawable.invalidateSelf()
		}
	}

	/**
	 * Attach the bakground to [activity].
	 */
	fun attach(activity: Activity) {
		// Set default background to current
		val current = activity.window.decorView.background
		windowBackground = current?.copy() ?: ColorDrawable(Color.BLACK)
		backgroundDrawable.setDrawable(staticBackgroundLayer, windowBackground)

		// Store size of window manager for this activity
		windowSize = WindowManager(activity).currentWindowMetrics.bounds.let {
			Size(it.right, it.bottom)
		}

		// Replace current background with service background
		activity.window.decorView.background = backgroundDrawable

		// Update
		update()
	}

	// Helper function for [setBackground]
	private fun ArrayList<String>?.getUrls(itemId: String?): List<String> {
		// Check for nullability
		if (itemId == null || isNullOrEmpty()) return emptyList()

		return mapIndexed { index, tag ->
			apiClient.GetImageUrl(itemId, ImageOptions().apply {
				imageType = ImageType.Backdrop
				setImageIndex(index)
				setTag(tag)
			})
		}
	}

	/**
	 * Use all available backdrops from [baseItem] as background.
	 */
	fun setBackground(baseItem: BaseItemDto?) {
		// Check if item is set and backgrounds are enabled
		if (baseItem == null || !userPreferences[UserPreferences.backdropEnabled])
			return clearBackgrounds()

		// Get all backdrop urls
		val itemBackdropUrls = baseItem.backdropImageTags.getUrls(baseItem.id)
		val parentBackdropUrls = baseItem.parentBackdropImageTags.getUrls(baseItem.parentBackdropItemId)
		val backdropUrls = itemBackdropUrls.union(parentBackdropUrls)

		if (backdropUrls.isEmpty()) return clearBackgrounds()

		// Cancel current loading job
		loadBackgroundsJob?.cancel()
		loadBackgroundsJob = scope.launch(Dispatchers.IO) {
			val backdropDrawables = backdropUrls
				.map { url ->
					Glide.with(context)
						.load(url)
						.override(windowSize.width, windowSize.height)
						.centerCrop()
						.submit()
				}
				.map { future -> async { future.get() } }
				.awaitAll()
				.onEach { it.colorFilter = colorFilter }
				.filterNotNull()

			backgrounds.clear()
			backgrounds.addAll(backdropDrawables)

			withContext(Dispatchers.Main) {
				// Go to first background
				currentIndex = 0
				update()
			}
		}
	}

	fun clearBackgrounds() {
		if (backgrounds.isEmpty()) return

		backgrounds.clear()
		update()
	}

	@MainThread
	private fun update() {
		val now = System.currentTimeMillis()
		if (lastBackgroundUpdate > now - UPDATE_INTERVAL)
			return setTimer(lastBackgroundUpdate - now + UPDATE_INTERVAL, false)

		lastBackgroundUpdate = now

		// Snapshot the current state if an animation is running and draw the new
		// background on top.
		if (backgroundAnimator.isRunning) {
			val current = backgroundDrawable
				.toBitmap(windowSize.width, windowSize.height)
				.toDrawable(context.resources)
			backgroundAnimator.end()
			backgroundDrawable.setDrawable(currentBackgroundLayer, current)
		}

		// Get next background to show
		if (currentIndex >= backgrounds.size) currentIndex = 0

		backgroundDrawable.setDrawable(
			nextBackgroundLayer,
			backgrounds.getOrElse(currentIndex) { windowBackground.copy() }
		)

		// Animate
		backgroundAnimator.start()

		// Set timer for next background
		if (backgrounds.size > 1) setTimer()
		else updateBackgroundTimerJob?.cancel()
	}

	private fun setTimer(updateDelay: Long = SLIDESHOW_DURATION, increaseIndex: Boolean = true) {
		updateBackgroundTimerJob?.cancel()
		updateBackgroundTimerJob = scope.launch {
			delay(updateDelay)

			if (increaseIndex) currentIndex++

			update()
		}
	}

	private fun Drawable.copy() = constantState!!.newDrawable().mutate()
}
