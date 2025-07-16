package com.yantraman.animalsoundmaker

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.ai.client.generativeai.GenerativeModel
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.texttospeech.v1.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rm.com.audiowave.AudioWaveView
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var playButton: Button
    private lateinit var inputText: EditText
    private lateinit var animalSpinner: Spinner
    private lateinit var resultText: TextView
    private lateinit var downloadAudio: TextView
    private lateinit var pitchSeekBar: SeekBar
    private lateinit var speedSeekBar: SeekBar
    private lateinit var pitchLabel: TextView
    private lateinit var speedLabel: TextView
    private lateinit var audioWaveProgressBar: AudioWaveView

    // Cache values to prevent redundant network calls
    private var lastInputText: String? = null
    private var lastSelectedAnimal: String? = null
    private var lastPitch: Float? = null
    private var lastSpeed: Float? = null
    private var lastTranslatedText: String? = null
    private var lastAudioData: ByteArray? = null

    private val progressAnim: ObjectAnimator by lazy {
        ObjectAnimator.ofFloat(audioWaveProgressBar, "progress", 0F, 100F).apply {
            interpolator = LinearInterpolator()
            repeatMode = ObjectAnimator.RESTART // Restart the animation after each cycle
            repeatCount = ObjectAnimator.INFINITE // Repeat infinitely
            duration = 1000 // Duration of each cycle (you can adjust it to fit your needs)
        }
    }

    private var nativeAd: NativeAd? = null
    private lateinit var adContainer: FrameLayout
    private val refreshInterval = 60000L // 60 seconds
    private val adRefreshHandler = Handler(Looper.getMainLooper())

    private val adRefreshRunnable = object : Runnable {
        override fun run() {
            loadNativeAd() // Refresh the ad
            adRefreshHandler.postDelayed(this, refreshInterval)
        }
    }

    private lateinit var interstitialAd: InterstitialAd
    private var isAdLoaded = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        loadNativeAd()
        adRefreshHandler.postDelayed(adRefreshRunnable, refreshInterval)
        loadInterstitialAd()
    }


    override fun onStart() {
        super.onStart()
        // Show the ad if available
        (application as? MyApplication)?.appOpenAdManager?.showAdIfAvailable(this)
    }

    override fun onResume() {
        super.onResume()
        adRefreshHandler.postDelayed(adRefreshRunnable, refreshInterval) // Restart ad refreshing
    }

    override fun onPause() {
        super.onPause()
        adRefreshHandler.removeCallbacks(adRefreshRunnable) // Stop refreshing ads
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            nativeAd?.destroy()
            mediaPlayer.release()
        } catch (e:Exception){
            e.printStackTrace()
        }
    }

    private fun initViews() {
        // Initialize UI Components
        playButton = findViewById(R.id.playButton)
        inputText = findViewById(R.id.inputText)
        animalSpinner = findViewById(R.id.animalSpinner)
        resultText = findViewById(R.id.resultText)
        downloadAudio= findViewById(R.id.downloadAudio)
        pitchSeekBar = findViewById(R.id.pitchSeekBar)
        speedSeekBar = findViewById(R.id.speedSeekBar)
        pitchLabel = findViewById(R.id.pitchLabel)
        speedLabel = findViewById(R.id.speedLabel)
        audioWaveProgressBar = findViewById(R.id.audioWaveProgressBar)
        adContainer = findViewById(R.id.adContainer)

        // Setup Spinner Listener
        val adapter = ArrayAdapter.createFromResource(
            this, R.array.animals, R.layout.spinner_text
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        animalSpinner.adapter = adapter


        animalSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedAnimal = animalSpinner.selectedItem.toString()
                setDefaultSeekBarValues(selectedAnimal.split(" ")[1])
                playButton.text = "Play Sound for $selectedAnimal"
                hideProgress()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // SeekBar Change Listeners
        pitchSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pitchValue = (progress - 10) / 2.0f
                pitchLabel.text = "Pitch: $pitchValue"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speedValue = progress / 10.0f
                speedLabel.text = "Speed: $speedValue"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Play Button Click Listener
        playButton.setOnClickListener {

            hideKeyBoard()
            showProgress()

            val input = inputText.text.toString()
            val selectedAnimal = animalSpinner.selectedItem.toString().split(" ")[1]
            val pitch = (pitchSeekBar.progress - 10) / 2.0f
            val speed = speedSeekBar.progress / 10.0f

            // Avoid redundant API calls
            if (input == lastInputText && selectedAnimal == lastSelectedAnimal &&
                pitch == lastPitch && speed == lastSpeed
            ) {
                lastAudioData?.let { playGeneratedAudio(it) }
                return@setOnClickListener
            }

            CoroutineScope(Dispatchers.IO).launch {
                val translatedText = translateToAnimalSpeech(input, selectedAnimal)
                runOnUiThread {
                    resultText.text = translatedText
                }

                val audioData = generateAnimalSound(translatedText, selectedAnimal, pitch, speed)
                if (audioData != null) {
                    lastInputText = input
                    lastSelectedAnimal = selectedAnimal
                    lastPitch = pitch
                    lastSpeed = speed
                    lastTranslatedText = translatedText
                    lastAudioData = audioData

                    playGeneratedAudio(audioData)
                }
            }
        }

        val fab: FloatingActionButton = findViewById(R.id.fab)
        fab.setOnClickListener {
            val url = "https://play.google.com/store/apps/dev?id=5612174818411350112"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }
    }

    private fun hideKeyBoard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val currentFocusView = currentFocus
        currentFocusView?.let {
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }


    private fun setDefaultSeekBarValues(animal: String) {
        val defaultPitch = getAnimalPitch(animal)
        val defaultSpeed = getAnimalSpeakingRate(animal)

        pitchSeekBar.progress = (defaultPitch * 2 + 10).toInt()
        speedSeekBar.progress = (defaultSpeed * 10).toInt()

        pitchLabel.text = "Pitch: $defaultPitch"
        speedLabel.text = "Speed: $defaultSpeed"
    }

    private suspend fun translateToAnimalSpeech(text: String, animal: String): String {
        return try {
            val generativeModel = GenerativeModel(
                modelName = "gemini-2.0-flash-exp",
                apiKey = getString(R.string.ai_key)
            )

            val prompt = """
            Translate this human message into $animal sounds only:
            "$text"
            Respond ONLY with the converted sounds text, no explanations.
            """.trimIndent()

            generativeModel.generateContent(prompt).text?.replace(Regex("""\*.*?\*"""), "")?.trim()
                ?: "hello hello"

        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun generateAnimalSound(text: String, animal: String, pitch: Float, speed: Float): ByteArray? {
        return try {
            val credentials = assets.open("service_account.json").use {
                GoogleCredentials.fromStream(it)
            }

            val textToSpeechSettings = TextToSpeechSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build()

            val textToSpeechClient = TextToSpeechClient.create(textToSpeechSettings)

            val ssmlText = """
            <speak>
                <prosody pitch="${pitch}st" rate="${speed}">
                    ${getAnimalSoundEffect(animal)} ${text} ${getAnimalSoundEffect(animal)}
                </prosody>
            </speak>
        """.trimIndent()

            val input = SynthesisInput.newBuilder().setSsml(ssmlText).build()
            val voice = VoiceSelectionParams.newBuilder()
                .setLanguageCode("en-US")
                .setSsmlGender(SsmlVoiceGender.MALE)
                .setName(getAnimalVoice(animal))
                .build()
            val audioConfig = AudioConfig.newBuilder()
                .setAudioEncoding(AudioEncoding.LINEAR16)
                .build()

            textToSpeechClient.synthesizeSpeech(input, voice, audioConfig).audioContent.toByteArray()
        } catch (e: Exception) {
            Log.e("TTS", "Error generating speech: ${e.message}")
            runOnUiThread {
                hideProgress()
            }
            null
        }
    }

    private fun getAnimalVoice(animal: String): String {
        return when (animal.lowercase()) {
            "dog", "wolf", "fox" -> "en-US-Wavenet-D"  // Deep, rough
            "cat", "parrot", "rabbit" -> "en-US-Wavenet-F"  // High-pitched
            "cow", "bear", "elephant" -> "en-US-Wavenet-G"  // Low-pitched
            "bird", "squirrel", "monkey" -> "en-US-Wavenet-H"  // Chirpy, fast
            "horse", "donkey", "goat" -> "en-US-Wavenet-B"
            "sheep", "pig", "kangaroo" -> "en-US-Wavenet-C"
            "dolphin", "whale", "seal" -> "en-US-Wavenet-E"  // Higher-pitched for marine sounds
            "frog", "owl", "snake" -> "en-US-Wavenet-I"
            "lion", "tiger" -> "en-US-Wavenet-J"
            "penguin", "duck", "chicken" -> "en-US-Wavenet-K"

            else -> "en-US-Wavenet-A" // Default voice for unsupported animals
        }
    }

    private fun getAnimalPitch(animal: String): Float {
        return when (animal.lowercase()) {
            "dog" -> -5.0f
            "cat" -> 8.0f
            "cow" -> -10.0f
            "bird" -> 12.0f
            "horse" -> -4.0f
            "sheep" -> 6.0f
            "goat" -> 7.0f
            "rabbit" -> 10.0f
            "elephant" -> -15.0f
            "monkey" -> 14.0f
            "dolphin" -> 16.0f
            "frog" -> -2.0f
            "owl" -> 3.0f
            "snake" -> -1.0f
            "parrot" -> 9.0f
            "chicken" -> 11.0f
            "pig" -> -6.0f
            "duck" -> 5.0f
            "donkey" -> -7.0f
            "wolf" -> -3.0f
            "fox" -> 4.0f
            "bear" -> -8.0f
            "lion" -> -12.0f
            "tiger" -> -11.0f
            "penguin" -> 6.5f
            "seal" -> -9.0f
            "whale" -> -20.0f
            "kangaroo" -> 7.5f
            "squirrel" -> 13.0f
            else -> 0.0f
        }
    }

    private fun getAnimalSpeakingRate(animal: String): Float {
        return when (animal.lowercase()) {
            "dog" -> 0.8f
            "cat" -> 1.2f
            "cow" -> 0.6f
            "bird" -> 1.5f
            "horse" -> 0.9f
            "sheep" -> 1.0f
            "goat" -> 1.1f
            "rabbit" -> 1.4f
            "elephant" -> 0.5f
            "monkey" -> 1.6f
            "dolphin" -> 1.8f
            "frog" -> 0.7f
            "owl" -> 0.85f
            "snake" -> 0.75f
            "parrot" -> 1.7f
            "chicken" -> 1.3f
            "pig" -> 0.65f
            "duck" -> 1.1f
            "donkey" -> 0.55f
            "wolf" -> 0.95f
            "fox" -> 1.25f
            "bear" -> 0.7f
            "lion" -> 0.5f
            "tiger" -> 0.6f
            "penguin" -> 1.2f
            "seal" -> 0.8f
            "whale" -> 0.4f
            "kangaroo" -> 1.3f
            "squirrel" -> 1.6f
            else -> 1.0f
        }
    }

    private fun getAnimalSoundEffect(animal: String): String {
        return when (animal.lowercase()) {
            "dog" -> "Woof woof!"
            "cat" -> "Meow meow!"
            "cow" -> "Moo moo!"
            "bird" -> "Chirp chirp!"
            "horse" -> "Neigh!"
            "sheep" -> "Baa baa!"
            "goat" -> "Bleat!"
            "rabbit" -> "Sniff sniff!"
            "elephant" -> "Trumpet!!! Rumble..."
            "monkey" -> "Ooo ooo aa aa!"
            "dolphin" -> "Click click!"
            "frog" -> "Ribbit ribbit!"
            "owl" -> "Hoot hoot!"
            "snake" -> "Ssss ssss!"
            "parrot" -> "Squawk!"
            "chicken" -> "Cluck cluck!"
            "pig" -> "Oink oink!"
            "duck" -> "Quack quack!"
            "donkey" -> "Hee-haw!"
            "wolf" -> "Awoooo!"
            "fox" -> "Ring-ding-ding!"
            "bear" -> "Grrr!"
            "lion" -> "Roar!"
            "tiger" -> "Grrr!"
            "penguin" -> "Squawk!"
            "seal" -> "Arf arf!"
            "whale" -> "Whooo!"
            "kangaroo" -> "Thump thump!"
            "squirrel" -> "Chatter chatter!"
            else -> "Hello Hello"
        }
    }

    private fun playGeneratedAudio(audioData: ByteArray) {
        val tempFile = File.createTempFile("${lastSelectedAnimal}_speech_", ".mp3", cacheDir)
        FileOutputStream(tempFile).use { it.write(audioData) }

       runOnUiThread {
           mediaPlayer = MediaPlayer()
           mediaPlayer.setDataSource(tempFile.absolutePath)
           mediaPlayer.prepare()
           mediaPlayer.start()

           mediaPlayer.setOnCompletionListener { mp ->
               downloadAudio.visibility = View.VISIBLE
               downloadAudio.setOnClickListener {
                   downloadAudioFile(tempFile)
               }
               hideProgress()
               mp.reset()
               mp.release()
               if (isAdLoaded) {
                   Toast.makeText(this, "Ad will be shown shortly", Toast.LENGTH_SHORT).show()
                   Handler(Looper.getMainLooper()).postDelayed({
                           showInterstitialAds()
                       }, 2000
                   )
               }
           }
       }
    }

    private fun downloadAudioFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp3"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Download Audio"))
    }


    private fun showProgress(){
        audioWaveProgressBar.visibility = View.VISIBLE
        audioWaveProgressBar.setRawData(assets.open("sample.wav").readBytes()) { progressAnim.start() }
        playButton.visibility = View.GONE
    }

    private fun hideProgress(){
        progressAnim.end()  // End the animation
        progressAnim.cancel()  // Cancel any ongoing animations
        progressAnim.removeAllListeners()
        audioWaveProgressBar.visibility = View.GONE
        playButton.visibility = View.VISIBLE
    }



    /* ads */

    private fun loadNativeAd() {
        val adLoader = AdLoader.Builder(this, getString(R.string.gms_ads_native_unit_id))
            .forNativeAd { ad ->
                nativeAd?.destroy() // Destroy previous ad to prevent memory leaks
                nativeAd = ad
                val adView = layoutInflater.inflate(R.layout.native_ad_layout, null) as NativeAdView
                populateNativeAdView(ad, adView)
                adContainer.removeAllViews()
                adContainer.addView(adView)
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.e("AdMob", "Native ad failed to load: ${adError.message}")
                    // adRefreshHandler.postDelayed({ loadNativeAd() }, refreshInterval) // Retry after 60 seconds
                }
            })
            .build()

        adLoader.loadAd(AdRequest.Builder().build())
    }

    private fun populateNativeAdView(nativeAd: NativeAd, adView: NativeAdView) {
        val headlineView = adView.findViewById<TextView>(R.id.ad_headline)
        val mediaView = adView.findViewById<MediaView>(R.id.ad_media)
        val callToActionView = adView.findViewById<Button>(R.id.ad_call_to_action)

        adView.headlineView = headlineView
        adView.mediaView = mediaView
        adView.callToActionView = callToActionView

        headlineView.text = nativeAd.headline
        callToActionView.text = nativeAd.callToAction ?: "Learn More"

        adView.setNativeAd(nativeAd)
    }

    private fun loadInterstitialAd() {
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(
            this,
            getString(R.string.gms_ads_interstitial_unit_id),
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    isAdLoaded = true
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.e("AdMob", "Interstitial ad failed to load: ${adError.message}")
                    isAdLoaded = false
                }
            }
        )
    }

    private fun showInterstitialAds() {
        if (isAdLoaded) {

            interstitialAd.show(this)
            interstitialAd.fullScreenContentCallback = object : FullScreenContentCallback() {

                override fun onAdDismissedFullScreenContent() {
                    // Load next ad after the current one is dismissed
                    loadInterstitialAd()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    Log.e("AdMob", "Interstitial ad failed to show: ${adError.message}")
                }
            }
        }
    }

}