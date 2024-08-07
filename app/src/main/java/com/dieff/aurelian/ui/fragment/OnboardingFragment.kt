package com.dieff.aurelian.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.dieff.aurelian.R

class OnboardingFragment : Fragment() {

    private lateinit var webView: WebView
    private lateinit var textureLabel: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_onboarding, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        webView = view.findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true

        webView.addJavascriptInterface(WebAppInterface(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                updateTextureLabel()
            }
        }

        webView.loadUrl("file:///android_asset/index.html")

        view.findViewById<Button>(R.id.toggleBackgroundButton).setOnClickListener {
            webView.evaluateJavascript("Android.toggleBackground()", null)
        }

        view.findViewById<Button>(R.id.cycleTextureButton).setOnClickListener {
            webView.evaluateJavascript("Android.cycleTexture()", null)
            updateTextureLabel()
        }

        view.findViewById<SeekBar>(R.id.brightnessSlider).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress / 100.0
                webView.evaluateJavascript("Android.setBrightness($value)", null)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        view.findViewById<Button>(R.id.moveBadgeButton).setOnClickListener {
            webView.evaluateJavascript("Android.moveBadge()", null)
        }

        textureLabel = view.findViewById(R.id.textureLabel)
    }

    private fun updateTextureLabel() {
        webView.evaluateJavascript("Android.getCurrentTexture()") { result ->
            activity?.runOnUiThread {
                textureLabel.text = "Current Texture: ${result.trim('"')}"
            }
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun showToast(message: String) {
            activity?.runOnUiThread {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}