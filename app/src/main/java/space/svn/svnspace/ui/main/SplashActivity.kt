package space.svn.svnspace.ui.main

import android.content.Intent
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import space.svn.svnspace.databinding.ActivitySplashBinding

/** Glowing cloud over a planet horizon, then straight into the app. */
class SplashActivity : AppCompatActivity() {

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

        // Blue-to-violet wordmark, like the logo.
        binding.txtWordmark.post {
            val w = binding.txtWordmark.width.toFloat().coerceAtLeast(1f)
            binding.txtWordmark.paint.shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(0xFF7DD3FC.toInt(), 0xFF818CF8.toInt(), 0xFFC084FC.toInt()),
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
