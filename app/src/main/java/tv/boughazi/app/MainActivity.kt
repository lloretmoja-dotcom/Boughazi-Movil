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
