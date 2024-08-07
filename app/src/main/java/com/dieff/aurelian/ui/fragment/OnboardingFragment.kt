package com.dieff.aurelian.ui.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.dieff.aurelian.R

class OnboardingFragment : Fragment() {

    private lateinit var webView: WebView
    private lateinit var fallbackImageView: ImageView
    private lateinit var textureLabel: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Log.d("OnboardingFragment", "onCreateView called")
        return inflater.inflate(R.layout.fragment_onboarding, container, false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("OnboardingFragment", "onViewCreated called")

        webView = view.findViewById(R.id.webView)
        fallbackImageView = view.findViewById(R.id.fallbackImageView)
        textureLabel = view.findViewById(R.id.textureLabel)

        setupWebView()
        setupControls(view)
        setupNavigation(view)
        listAssetFiles()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        Log.d("OnboardingFragment", "setupWebView called")

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        webView.addJavascriptInterface(WebAppInterface(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                Log.e("OnboardingFragment", "WebView error: ${error?.description}, Error code: ${error?.errorCode}")
                Log.e("OnboardingFragment", "Failed URL: ${request?.url}")
                super.onReceivedError(view, request, error)
                showFallbackImage()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                Log.d("OnboardingFragment", "WebView page finished loading: $url")
                super.onPageFinished(view, url)
                updateTextureLabel()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("WebView Console", "${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
                return true
            }
        }

        try {
            Log.d("OnboardingFragment", "Attempting to load HTML content")
            val htmlContent = loadHtmlFromAssets("onboarding.html")
            Log.d("OnboardingFragment", "HTML content loaded successfully. First 100 characters: ${htmlContent.take(100)}")
            webView.loadDataWithBaseURL("file:///android_asset/", htmlContent, "text/html", "UTF-8", null)
        } catch (e: Exception) {
            Log.e("OnboardingFragment", "Error loading HTML: ${e.message}")
            e.printStackTrace()
            showFallbackImage()
        }
    }

    private fun loadHtmlFromAssets(fileName: String): String {
        return try {
            context?.assets?.open(fileName)?.use { inputStream ->
                inputStream.bufferedReader().use { it.readText() }
            } ?: throw IllegalStateException("Failed to open file: $fileName")
        } catch (e: Exception) {
            Log.e("OnboardingFragment", "Error reading HTML file: ${e.message}")
            throw e
        }
    }

    private fun setupControls(view: View) {
        Log.d("OnboardingFragment", "setupControls called")

        view.findViewById<Button>(R.id.toggleBackgroundButton).setOnClickListener {
            Log.d("OnboardingFragment", "Toggle background button clicked")
            webView.evaluateJavascript("Android.toggleBackground()", null)
        }

        view.findViewById<Button>(R.id.cycleTextureButton).setOnClickListener {
            Log.d("OnboardingFragment", "Cycle texture button clicked")
            webView.evaluateJavascript("Android.cycleTexture()", null)
            updateTextureLabel()
        }

        view.findViewById<SeekBar>(R.id.brightnessSlider).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                Log.d("OnboardingFragment", "Brightness changed to: $progress")
                val value = progress / 100.0
                webView.evaluateJavascript("Android.setBrightness($value)", null)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        view.findViewById<Button>(R.id.moveBadgeButton).setOnClickListener {
            Log.d("OnboardingFragment", "Move badge button clicked")
            webView.evaluateJavascript("Android.moveBadge()", null)
        }
    }

    private fun setupNavigation(view: View) {
        view.findViewById<Button>(R.id.nextButton).setOnClickListener {
            Log.d("OnboardingFragment", "Next button clicked")
            findNavController().navigate(R.id.action_onboardingFragment_to_multiGraphFragment)
        }
    }

    private fun updateTextureLabel() {
        Log.d("OnboardingFragment", "updateTextureLabel called")
        webView.evaluateJavascript("Android.getCurrentTexture()") { result ->
            Log.d("OnboardingFragment", "Current texture: $result")
            activity?.runOnUiThread {
                textureLabel.text = "Current Texture: ${result.trim('"')}"
            }
        }
    }

    private fun showFallbackImage() {
        Log.d("OnboardingFragment", "showFallbackImage called")
        webView.visibility = View.GONE
        fallbackImageView.visibility = View.VISIBLE
    }

    private fun listAssetFiles() {
        try {
            val assetFiles = context?.assets?.list("") ?: return
            Log.d("OnboardingFragment", "Files in assets folder: ${assetFiles.joinToString(", ")}")
        } catch (e: Exception) {
            Log.e("OnboardingFragment", "Error listing asset files: ${e.message}")
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun showToast(message: String) {
            Log.d("OnboardingFragment", "showToast called with message: $message")
            activity?.runOnUiThread {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}