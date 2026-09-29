package tv.boughazi.app

import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val authRepository = AuthRepository()
    private val codeRepository = CodeRepository()
    private val channelRepository = ChannelRepository()
    private val presenceRepository = PresenceRepository()
    private lateinit var sessionManager: SessionManager

    private var session: UserSession? = null
    private var allChannels: List<Channel> = emptyList()
    private var categories: List<String> = emptyList()
    private var currentIndex = -1

    // Qué país/categoría se está viendo AHORA MISMO en la lista de canales
    // (si está abierta). Sirve para poder refrescar esa lista sola cuando
    // llegan canales nuevos o se borran caídos, sin que la persona tenga
    // que cerrar la aplicación y volver a abrirla para verlo.
    private var currentlyViewedCategory: String? = null

    private var exoPlayer: ExoPlayer? = null
    private val numberBuffer = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    private var osdHideRunnable: Runnable? = null
    private var numberEntryRunnable: Runnable? = null
    private var presenceRunnable: Runnable? = null
    private var channelRefreshRunnable: Runnable? = null

    companion object {
        private const val CHANNEL_REFRESH_INTERVAL_MS = 5 * 60 * 1000L // 5 minutos
    }

    private lateinit var welcomeSection: View
    private lateinit var loginPanelSection: View
    private lateinit var signUpPanelSection: View
    private lateinit var forgotPanelSection: View
    private lateinit var codeSection: View
    private lateinit var mainSection: View
    private lateinit var playerView: PlayerView
    private lateinit var categoriesColumn: View
    private lateinit var categoriesList: RecyclerView
    private lateinit var channelsList: RecyclerView
    private lateinit var osdContainer: View
    private lateinit var osdNumber: TextView
    private lateinit var osdName: TextView
    private lateinit var loadingText: TextView
    private lateinit var debugInfoText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateFullscreenMode(resources.configuration.orientation)
        sessionManager = SessionManager(this)
        MobileAds.initialize(this)

        welcomeSection = findViewById(R.id.welcomeSection)
        loginPanelSection = findViewById(R.id.loginPanelSection)
        signUpPanelSection = findViewById(R.id.signUpPanelSection)
        forgotPanelSection = findViewById(R.id.forgotPanelSection)
        codeSection = findViewById(R.id.codeSection)
        mainSection = findViewById(R.id.mainSection)
        playerView = findViewById(R.id.playerView)
        categoriesColumn = findViewById(R.id.categoriesColumn)
        categoriesList = findViewById(R.id.categoriesList)
        channelsList = findViewById(R.id.channelsList)
        osdContainer = findViewById(R.id.osdContainer)
        osdNumber = findViewById(R.id.osdNumber)
        osdName = findViewById(R.id.osdName)
        loadingText = findViewById(R.id.loadingText)
        debugInfoText = findViewById(R.id.debugInfoText)
        val tapOverlay: View = findViewById(R.id.tapOverlay)
        val menuButton: View = findViewById(R.id.menuButton)

        // En la tele esto se abre con el botón de guía/menú del mando. En
        // el móvil no hay mando, así que tocar la pantalla mientras se ve
        // un canal hace exactamente lo mismo: abre o cierra la lista de
        // canales para poder elegir otro. El toque se recoge en la capa
        // transparente de encima ("tapOverlay"), no en el propio vídeo,
        // porque el reproductor se queda con el toque para sus propios
        // gestos y nunca llegaba a notar el click.
        tapOverlay.setOnClickListener {
            if (mainSection.visibility == View.VISIBLE) {
                if (categoriesColumn.visibility == View.VISIBLE) hideChannelBrowser() else showChannelBrowser()
            }
        }

        // Botón de menú (☰) fijo, siempre encima del vídeo. Hace lo mismo
        // que tocar la pantalla, pero al ser un botón concreto y visible
        // es más fácil de encontrar. Se abre directamente en el canal que
        // se está viendo en ese momento, con su logo, y al volver a
        // tocarlo se cierra y se regresa al vídeo.
        menuButton.setOnClickListener {
            if (mainSection.visibility == View.VISIBLE) {
                if (categoriesColumn.visibility == View.VISIBLE) hideChannelBrowser() else openBrowserAtCurrentChannel()
            }
        }

        setupWelcomeSection()
        setupLoginPanel()
        setupSignUpPanel()
        setupForgotPanel()
        setupCodeSection()
        setupAdBanner()

        val saved = sessionManager.load()
        if (saved == null) {
            showOnly(welcomeSection)
        } else {
            session = saved
            if (saved.hasLinkedCode) {
                enterMainSection()
            } else {
                lifecycleScope.launch {
                    val already = codeRepository.checkAlreadyLinked(saved)
                    if (already) {
                        saved.hasLinkedCode = true
                        sessionManager.markCodeLinked()
                        enterMainSection()
                    } else {
                        showOnly(codeSection)
                    }
                }
            }
        }
    }

    /**
     * Cuando el móvil está girado en horizontal, el canal se ve en pantalla
     * completa de verdad: se esconden la barra de arriba (hora, wifi,
     * batería) y la barra de abajo del móvil, para que el vídeo ocupe todo
     * el hueco. Cuando el móvil vuelve a estar en vertical, esas barras
     * se vuelven a ver normales.
     */
    private fun updateFullscreenMode(orientation: Int) {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateFullscreenMode(newConfig.orientation)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            updateFullscreenMode(resources.configuration.orientation)
        }
    }

    /**
     * La pantalla de bienvenida solo tiene botones. Cada uno abre su
     * propio panel aparte, con sus propias casillas — así nunca hay
     * dudas sobre para qué sirve cada campo.
     */
    private fun setupWelcomeSection() {
        findViewById<View>(R.id.welcomeEntrarBtn).setOnClickListener {
            showOnly(loginPanelSection)
        }
        findViewById<View>(R.id.welcomeCrearCuentaBtn).setOnClickListener {
            showOnly(signUpPanelSection)
        }
        findViewById<View>(R.id.welcomeOlvidoBtn).setOnClickListener {
            showOnly(forgotPanelSection)
        }
    }

    private fun setupLoginPanel() {
        val emailField = findViewById<EditText>(R.id.loginEmail)
        val passwordField = findViewById<EditText>(R.id.loginPassword)
        val errorText = findViewById<TextView>(R.id.loginError)

        findViewById<View>(R.id.loginSubmitBtn).setOnClickListener {
            val email = emailField.text.toString().trim()
            val password = passwordField.text.toString()
            if (email.isEmpty() || password.isEmpty()) {
                showError(errorText, "Escribe tu correo y tu contraseña.")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                when (val result = authRepository.signIn(email, password)) {
                    is AuthResult.Success -> onAuthSuccess(result.session)
                    is AuthResult.Failure -> showError(errorText, result.message)
                }
            }
        }

        findViewById<View>(R.id.loginBackBtn).setOnClickListener {
            passwordField.text.clear()
            errorText.visibility = View.GONE
            showOnly(welcomeSection)
        }
    }

    private fun setupSignUpPanel() {
        val emailField = findViewById<EditText>(R.id.signUpEmail)
        val passwordField = findViewById<EditText>(R.id.signUpPassword)
        val errorText = findViewById<TextView>(R.id.signUpError)

        findViewById<View>(R.id.signUpSubmitBtn).setOnClickListener {
            val email = emailField.text.toString().trim()
            val password = passwordField.text.toString()
            if (email.isEmpty() || password.length < 6) {
                showError(errorText, "Escribe un correo y una contraseña de al menos 6 caracteres.")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                when (val result = authRepository.signUp(email, password)) {
                    is AuthResult.Success -> onAuthSuccess(result.session)
                    is AuthResult.Failure -> showError(errorText, result.message)
                }
            }
        }

        findViewById<View>(R.id.signUpBackBtn).setOnClickListener {
            passwordField.text.clear()
            errorText.visibility = View.GONE
            showOnly(welcomeSection)
        }
    }

    private fun setupForgotPanel() {
        val emailField = findViewById<EditText>(R.id.forgotEmail)
        val errorText = findViewById<TextView>(R.id.forgotError)
        val statusText = findViewById<TextView>(R.id.forgotStatus)

        findViewById<View>(R.id.forgotSubmitBtn).setOnClickListener {
            val email = emailField.text.toString().trim()
            statusText.visibility = View.GONE
            if (email.isEmpty()) {
                showError(errorText, "Escribe tu correo arriba y vuelve a tocar aquí.")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val result = authRepository.sendPasswordReset(email)
                if (result is AuthResult.Failure) {
                    showError(errorText, result.message)
                } else {
                    errorText.visibility = View.GONE
                    statusText.text = "Te hemos mandado un enlace a tu correo para cambiar la contraseña."
                    statusText.visibility = View.VISIBLE
                }
            }
        }

        findViewById<View>(R.id.forgotBackBtn).setOnClickListener {
            errorText.visibility = View.GONE
            statusText.visibility = View.GONE
            showOnly(welcomeSection)
        }
    }

    private fun onAuthSuccess(newSession: UserSession) {
        session = newSession
        sessionManager.save(newSession)
        lifecycleScope.launch {
            val already = codeRepository.checkAlreadyLinked(newSession)
            if (already) {
                newSession.hasLinkedCode = true
                sessionManager.markCodeLinked()
                enterMainSection()
            } else {
                showOnly(codeSection)
            }
        }
    }

    private fun setupCodeSection() {
        val codeInput = findViewById<EditText>(R.id.codeInput)
        val errorText = findViewById<TextView>(R.id.codeError)

        findViewById<View>(R.id.codeSubmitBtn).setOnClickListener {
            val code = codeInput.text.toString().trim()
            val currentSession = session ?: return@setOnClickListener
            if (code.isEmpty()) {
                showError(errorText, "Escribe el código que te han dado.")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                attemptRedeem(currentSession, code, errorText, allowRetry = true)
            }
        }
    }

    private suspend fun attemptRedeem(
        currentSession: UserSession,
        code: String,
        errorText: TextView,
        allowRetry: Boolean
    ) {
        when (val result = codeRepository.redeemCode(currentSession, code)) {
            is RedeemResult.Success -> {
                currentSession.hasLinkedCode = true
                sessionManager.markCodeLinked()
                enterMainSection()
            }
            is RedeemResult.Failure -> {
                if (allowRetry && (result.httpStatus == 401 || result.httpStatus == 403)) {
                    when (val refreshed = authRepository.refreshSession(currentSession.refreshToken)) {
                        is AuthResult.Success -> {
                            val renewed = refreshed.session.copy(hasLinkedCode = currentSession.hasLinkedCode)
                            session = renewed
                            sessionManager.save(renewed)
                            attemptRedeem(renewed, code, errorText, allowRetry = false)
                        }
                        is AuthResult.Failure -> {
                            showError(
                                errorText,
                                "Tu sesión caducó y no se pudo renovar. Cierra la app, entra otra vez con tu Gmail y prueba el código de nuevo."
                            )
                        }
                    }
                } else {
                    showError(
                        errorText,
                        "Ese código no es válido o ya se ha usado. (Detalle: HTTP ${result.httpStatus} — ${result.detail})"
                    )
                }
            }
        }
    }

    private fun enterMainSection() {
        showOnly(mainSection)
        loadingText.visibility = View.VISIBLE
        val currentSession = session ?: return

        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            playerView.player = player
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    handler.postDelayed({
                        if (currentIndex in allChannels.indices) {
                            playChannel(currentIndex)
                        }
                    }, 3000)
                }
            })
        }

        categoriesList.layoutManager = LinearLayoutManager(this)
        channelsList.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            loadingText.text = "Cargando canales…"
            loadChannels(currentSession, allowRetry = true)
        }
        startChannelAutoRefresh()
    }

    private fun startChannelAutoRefresh() {
        channelRefreshRunnable?.let { handler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                val currentSession = session
                if (currentSession != null) {
                    lifecycleScope.launch { refreshChannelsQuietly(currentSession, allowRetry = true) }
                }
                handler.postDelayed(this, CHANNEL_REFRESH_INTERVAL_MS)
            }
        }
        channelRefreshRunnable = runnable
        handler.postDelayed(runnable, CHANNEL_REFRESH_INTERVAL_MS)
    }

    private fun updateDebugInfo(loaded: Int, categoriesCount: Int, totalOnServer: Int?) {
        // Aviso de pruebas ya no visible: la app está lista para usuarios
        // reales. Se deja la función (no se borra) por si hiciera falta
        // reactivar el aviso más adelante para investigar algo.
        debugInfoText.visibility = View.GONE
    }

    private suspend fun refreshChannelsQuietly(currentSession: UserSession, allowRetry: Boolean) {
        when (val result = channelRepository.fetchChannels(currentSession)) {
            is ChannelsResult.Success -> {
                val hadChannelsBefore = allChannels.isNotEmpty()
                val playingId = allChannels.getOrNull(currentIndex)?.id

                allChannels = result.channels
                categories = allChannels.map { it.category }.distinct()
                updateDebugInfo(allChannels.size, categories.size, result.totalReportedByServer)

                categoriesList.adapter = RowAdapter(
                    lifecycleScope,
                    categories.map { RowItem(title = it) }
                ) { position -> onCategorySelected(categories[position]) }

                // Si la persona tiene abierta la lista de canales de un país
                // en este momento, la volvemos a rellenar con los datos
                // recién traídos — así, si algún canal se ha borrado (por
                // estar caído) o se ha añadido uno nuevo, se ve solo, sin
                // tener que cerrar la aplicación y volver a abrirla.
                currentlyViewedCategory
                    ?.takeIf { it in categories && channelsList.visibility == View.VISIBLE }
                    ?.let { onCategorySelected(it) }

                currentIndex = playingId
                    ?.let { id -> allChannels.indexOfFirst { it.id == id } }
                    ?.takeIf { it >= 0 }
                    ?: currentIndex.coerceIn(0, (allChannels.size - 1).coerceAtLeast(0))

                if (!hadChannelsBefore && allChannels.isNotEmpty()) {
                    loadingText.visibility = View.GONE
                    playChannel(0)
                    startPresenceHeartbeat()
                }
            }
            is ChannelsResult.Failure -> {
                if (allowRetry && (result.httpStatus == 401 || result.httpStatus == 403)) {
                    when (val refreshed = authRepository.refreshSession(currentSession.refreshToken)) {
                        is AuthResult.Success -> {
                            val renewed = refreshed.session.copy(hasLinkedCode = currentSession.hasLinkedCode)
                            session = renewed
                            sessionManager.save(renewed)
                            refreshChannelsQuietly(renewed, allowRetry = false)
                        }
                        is AuthResult.Failure -> {
                            // Fallo silencioso: lo reintentamos solos en el próximo ciclo.
                        }
                    }
                }
                // Fallo silencioso también en los demás casos: se reintenta solo
                // en el siguiente ciclo, sin mostrar ningún aviso en pantalla.
            }
        }
    }

    private suspend fun loadChannels(currentSession: UserSession, allowRetry: Boolean) {
        when (val result = channelRepository.fetchChannels(currentSession)) {
            is ChannelsResult.Succ
