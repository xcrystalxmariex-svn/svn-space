package space.svn.svnspace.ui.main

import android.content.Intent
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.android.material.color.MaterialColors
import space.svn.svnspace.ui.common.ThemedActivity
import space.svn.svnspace.databinding.ActivitySplashBinding

/** Glowing cloud over a planet horizon, then straight into the app. */
class SplashActivity : ThemedActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val next = Runnable {
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Wordmark in the current theme's accent colors.
        val primary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val secondary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSecondary)
        val tertiary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorTertiary, secondary)
        binding.txtWordmark.post {
            val w = binding.txtWordmark.width.toFloat().coerceAtLeast(1f)
            binding.txtWordmark.paint.shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(primary, secondary, tertiary),
                null, Shader.TileMode.CLAMP
            )
            binding.txtWordmark.invalidate()
        }
        binding.imgLogo.alpha = 0f
        binding.imgLogo.scaleX = 0.85f
        binding.imgLogo.scaleY = 0.85f
        binding.imgLogo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(600).start()

        handler.postDelayed(next, 1400)
    }

    override fun onDestroy() {
        handler.removeCallbacks(next)
        super.onDestroy()
    }
}
