package com.micewine.emu.activities

import android.Manifest.permission
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MenuItem
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.Surface
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.navigation.NavigationView
import com.micewine.emu.CmdEntryPoint
import com.micewine.emu.CmdEntryPoint.Companion.requestConnection
import com.micewine.emu.ICmdEntryInterface
import com.micewine.emu.LorieView
import com.micewine.emu.R
import com.micewine.emu.activities.MainActivity.Companion.enableCpuCounter
import com.micewine.emu.activities.MainActivity.Companion.enableRamCounter
import com.micewine.emu.activities.MainActivity.Companion.getCpuInfo
import com.micewine.emu.activities.MainActivity.Companion.getMemoryInfo
import com.micewine.emu.activities.MainActivity.Companion.setSharedVars
import com.micewine.emu.controller.ControllerUtils.checkControllerAxis
import com.micewine.emu.controller.ControllerUtils.checkControllerButtons
import com.micewine.emu.controller.ControllerUtils.controllerMouseEmulation
import com.micewine.emu.controller.ControllerUtils.prepareButtonsAxisValues
import com.micewine.emu.controller.XKeyCodes.getXKeyScanCodes
import com.micewine.emu.core.WineWrapper
import com.micewine.emu.input.InputEventSender
import com.micewine.emu.input.InputStub
import com.micewine.emu.input.TouchInputHandler
import com.micewine.emu.input.TouchInputHandler.RenderStub.NullStub
import com.micewine.emu.utils.FullscreenWorkaround
import com.micewine.emu.utils.KeyInterceptor
import com.micewine.emu.views.OverlayView
import com.micewine.emu.views.OverlayView.CustomButtonData
import kotlinx.coroutines.launch

class EmulationActivity : AppCompatActivity(), View.OnApplyWindowInsetsListener {
    private var frm: FrameLayout? = null
    var orientation = 0
    private var mInputHandler: TouchInputHandler? = null
    private var service: ICmdEntryInterface? = null
    private var mClientConnected = false
    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        override fun onReceive(context: Context, intent: Intent) {
            if (CmdEntryPoint.ACTION_START == intent.action) {
                try {
                    Log.v("LorieBroadcastReceiver", "Got new ACTION_START intent")
                    val b = intent.getBundleExtra("")?.getBinder("")
                    service = ICmdEntryInterface.Stub.asInterface(b)
                    service?.asBinder()?.linkToDeath(
                        {
                            service = null
                            requestConnection()
                            Log.v("Lorie", "Disconnected")
                            runOnUiThread { clientConnectedStateChanged(false) } //recreate()); //onPreferencesChanged(""));
                        }, 0
                    )
                    onReceiveConnection()
                } catch (e: Exception) {
                    Log.e(
                        "MainActivity",
                        "Something went wrong while we extracted connection details from binder.",
                        e
                    )
                }
            } else if (ACTION_STOP == intent.action) {
                finishAffinity()
            } else if (GeneralSettings.ACTION_PREFERENCES_CHANGED == intent.action) {
                Log.d("MainActivity", "preference: " + intent.getStringExtra("key"))
                if ("additionalKbdVisible" != intent.getStringExtra("key")) onPreferencesChanged("")
            }
        }
    }
    private var mLorieKeyListener: View.OnKeyListener? = null
    private var drawerLayout: DrawerLayout? = null

    init {
        instance = this
    }
    @SuppressLint(
        "ClickableViewAccessibility",
        "UnspecifiedRegisterReceiverFlag"
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        preferences.registerOnSharedPreferenceChangeListener { _: SharedPreferences?, key: String? ->
            onPreferencesChanged(key)
        }

        if (enableCpuCounter) {
            lifecycleScope.launch {
                getCpuInfo()
            }
        }

        if (enableRamCounter) {
            lifecycleScope.launch {
                getMemoryInfo(this@EmulationActivity)
            }
        }

        prepareButtonsAxisValues(this)
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        window.setFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, 0)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_emulation)

        drawerLayout = findViewById(R.id.DrawerLayout)
        drawerLayout?.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        val lorieView = findViewById<LorieView>(R.id.lorieView)
        val lorieParent = lorieView.parent as View

        val overlayView: OverlayView = findViewById(R.id.overlayView)

        overlayView.addButton(CustomButtonData(1, "Enter", 50F, 50F, 150F, getXKeyScanCodes("Enter")))
        overlayView.addButton(CustomButtonData(2, "Right", 50F, 500F, 150F, getXKeyScanCodes("Right")))
        overlayView.addButton(CustomButtonData(3, "Left", 200F, 500F, 150F, getXKeyScanCodes("Left")))
        overlayView.addButton(CustomButtonData(4, "Up", 450F, 500F, 150F, getXKeyScanCodes("Up")))
        overlayView.addButton(CustomButtonData(5, "Down", 600F, 500F, 150F, getXKeyScanCodes("Down")))

        overlayView.visibility = View.INVISIBLE

        lifecycleScope.launch {
            controllerMouseEmulation(lorieView)
        }

        val nav = findViewById<NavigationView>(R.id.NavigationView)
        nav.setNavigationItemSelectedListener { item: MenuItem ->
            when (item.itemId) {
                R.id.exitFromEmulation -> {
                    drawerLayout?.closeDrawers()

                    WineWrapper.wineServer("--kill")

                    val intent = Intent(this, MainActivity::class.java).apply {
                        setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }

                    startActivityIfNeeded(intent, 0)
                }

                R.id.openCloseKeyboard -> {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    @Suppress("DEPRECATION")
                    imm.showSoftInput(lorieView, InputMethodManager.SHOW_FORCED)
                    drawerLayout?.closeDrawers()
                }

                R.id.setScreenStretch -> {
                    val editPrefs = preferences.edit()
                    editPrefs.putBoolean("displayStretch", !preferences.getBoolean("displayStretch", false))
                    editPrefs.apply()

                    lorieView.requestLayout()
                }

                R.id.openCloseOverlay -> {
                    if (overlayView.isVisible) {
                        overlayView.visibility = View.INVISIBLE
                    } else {
                        overlayView.visibility = View.VISIBLE
                    }

                    drawerLayout?.closeDrawers()
                }

                R.id.editControllerPreferences -> {
                    val i = Intent(this, ControllerMapper::class.java)
                    startActivity(i)
                }
            }
            true
        }
        mInputHandler = TouchInputHandler(this, object : NullStub() {
            override fun swipeDown() {}
        }, InputEventSender(lorieView))
        mLorieKeyListener = View.OnKeyListener { v: View?, k: Int, e: KeyEvent ->
            if (k == KeyEvent.KEYCODE_BACK) {
                if (e.isFromSource(InputDevice.SOURCE_MOUSE) || e.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) {
                    if (e.repeatCount != 0) // ignore auto-repeat
                        return@OnKeyListener true
                    if (e.action == KeyEvent.ACTION_UP || e.action == KeyEvent.ACTION_DOWN) lorieView.sendMouseEvent(
                        -1f,
                        -1f,
                        InputStub.BUTTON_RIGHT,
                        e.action == KeyEvent.ACTION_DOWN,
                        true
                    )
                    return@OnKeyListener true
                }
                if (e.scanCode == KEY_BACK && e.device.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC || e.scanCode == 0) {
                    if (e.action == KeyEvent.ACTION_UP) if (!drawerLayout?.isDrawerOpen(GravityCompat.START)!!) {
                        drawerLayout?.openDrawer(GravityCompat.START)
                    } else {
                        drawerLayout?.closeDrawers()
                    }
                    return@OnKeyListener true
                }
            } else if (k == KeyEvent.KEYCODE_VOLUME_DOWN) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI)
                return@OnKeyListener true
            } else if (k == KeyEvent.KEYCODE_VOLUME_UP) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI)
                return@OnKeyListener true
            }

            checkControllerButtons(lorieView, e)
            mInputHandler!!.sendKeyEvent(v, e)
        }
        lorieParent.setOnTouchListener { _: View?, e: MotionEvent? ->
            mInputHandler!!.handleTouchEvent(
                lorieParent,
                lorieView,
                e
            )
        }
        lorieParent.setOnHoverListener { _: View?, e: MotionEvent? ->
            mInputHandler!!.handleTouchEvent(
                lorieParent,
                lorieView,
                e
            )
        }
        lorieParent.setOnGenericMotionListener { _: View?, e: MotionEvent? ->
            mInputHandler!!.handleTouchEvent(
                lorieParent,
                lorieView,
                e
            )
        }
        lorieView.setOnCapturedPointerListener { _: View?, e: MotionEvent? ->
            mInputHandler!!.handleTouchEvent(
                lorieView,
                lorieView,
                e
            )
        }
        lorieParent.setOnCapturedPointerListener { _: View?, e: MotionEvent? ->
            mInputHandler!!.handleTouchEvent(
                lorieView,
                lorieView,
                e
            )
        }
        lorieView.setOnKeyListener(mLorieKeyListener)

        val callback = object : LorieView.Callback {
            override fun changed(
                sfc: Surface?,
                surfaceWidth: Int,
                surfaceHeight: Int,
                screenWidth: Int,
                screenHeight: Int
            ) {
                val framerate = (lorieView.display?.refreshRate ?: 30).toInt()
                mInputHandler?.handleHostSizeChanged(surfaceWidth, surfaceHeight)
                mInputHandler?.handleClientSizeChanged(screenWidth, screenHeight)
                LorieView.sendWindowChange(screenWidth, screenHeight, framerate)
                service?.let {
                    try {
                        it.windowChanged(sfc)
                    } catch (e: RemoteException) {
                        e.printStackTrace()
                    }
                }
            }
        }

        lorieView.setCallback(callback)

        registerReceiver(receiver, object : IntentFilter(CmdEntryPoint.ACTION_START) {
            init {
                addAction(GeneralSettings.ACTION_PREFERENCES_CHANGED)
                addAction(ACTION_STOP)
            }
        })

        // Taken from Stackoverflow answer https://stackoverflow.com/questions/7417123/android-how-to-adjust-layout-in-full-screen-mode-when-softkeyboard-is-visible/7509285#
        FullscreenWorkaround.assistActivity(this)
        requestConnection()
        onPreferencesChanged("")
        checkXEvents()

        setSharedVars(this)

        if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU && checkSelfPermission(permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(
                permission.POST_NOTIFICATIONS
            )
        ) {
            requestPermissions(arrayOf(permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        checkControllerAxis(lorieView, event!!)

        return true
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    fun setSize(v: View, width: Int, height: Int) {
        val p = v.layoutParams
        p.width = (width * getResources().displayMetrics.density).toInt()
        p.height = (height * getResources().displayMetrics.density).toInt()
        v.setLayoutParams(p)
        v.setMinimumWidth((width * getResources().displayMetrics.density).toInt())
        v.setMinimumHeight((height * getResources().displayMetrics.density).toInt())
    }

    fun onReceiveConnection() {
        try {
            if (service != null && service!!.asBinder().isBinderAlive) {
                Log.v("LorieBroadcastReceiver", "Extracting logcat fd.")
                val logcatOutput = service!!.getLogcatOutput()
                if (logcatOutput != null) LorieView.startLogcat(logcatOutput.detachFd())
                tryConnect()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e)
        }
    }

    private fun tryConnect() {
        if (mClientConnected) {
            return
        }
        try {
            Log.v("LorieBroadcastReceiver", "Extracting X connection socket.")
            val fd = if (service == null) null else service!!.getXConnection()
            if (fd != null) {
                LorieView.connect(fd.detachFd())
                lorieView.triggerCallback()
                clientConnectedStateChanged(true)
                LorieView.setClipboardSyncEnabled(true)
            } else handler.postDelayed({ tryConnect() }, 500)
        } catch (e: Exception) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e)
            service = null

            // We should reset the View for the case if we have sent it's surface to the client.
            lorieView.regenerate()
        }
    }

    @SuppressLint("WrongConstant")
    fun onPreferencesChanged(key: String?) {
        if ("additionalKbdVisible" == key) return
        val p = PreferenceManager.getDefaultSharedPreferences(this)
        val lorieView = lorieView
        val mode = 1
        mInputHandler!!.setInputMode(mode)
        mInputHandler!!.setTapToMove(false)
        mInputHandler!!.setPreferScancodes(true)
        mInputHandler!!.setPointerCaptureEnabled(false)
        if (!p.getBoolean(
                "pointerCapture",
                false
            ) && lorieView.hasPointerCapture()
        ) lorieView.releasePointerCapture()
        onWindowFocusChanged(true)
        LorieView.setClipboardSyncEnabled(false)
        lorieView.triggerCallback()
        if (checkSelfPermission(permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) KeyInterceptor.shutdown()
        val requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (getRequestedOrientation() != requestedOrientation) setRequestedOrientation(
            requestedOrientation
        )

        //Reset default input back to normal
        TouchInputHandler.STYLUS_INPUT_HELPER_MODE = 1
    }

    public override fun onResume() {
        super.onResume()
        lorieView.requestFocus()
        prepareButtonsAxisValues(this)
    }

    public override fun onPause() {
        super.onPause()
    }

    private val lorieView: LorieView
        get() = findViewById(R.id.lorieView)

    fun handleKey(e: KeyEvent): Boolean {
        mLorieKeyListener!!.onKey(lorieView, e.keyCode, e)
        return true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != orientation) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            var view = currentFocus
            if (view == null) {
                view = lorieView
                view.requestFocus()
            }
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
        orientation = newConfig.orientation
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        PreferenceManager.getDefaultSharedPreferences(this)
        val window = window

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        if (hasFocus) {
            getWindow().attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.statusBarColor = Color.BLACK
            window.navigationBarColor = Color.BLACK
        }
        window.setFlags(
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS, 0)
        if (hasFocus) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        (findViewById<View>(android.R.id.content) as FrameLayout).getChildAt(0).fitsSystemWindows = false
        if (hasFocus) {
            lorieView.regenerate()
        }
        lorieView.requestFocus()
    }

    public override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        PreferenceManager.getDefaultSharedPreferences(this)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        frm!!.setPadding(0, 0, 0, 0)
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsets): WindowInsets {
        handler.postDelayed({ lorieView.triggerCallback() }, 100)
        return insets
    }

    fun clientConnectedStateChanged(connected: Boolean) {
        runOnUiThread {
            PreferenceManager.getDefaultSharedPreferences(this)
            mClientConnected = connected
            lorieView.setVisibility(if (connected) View.VISIBLE else View.INVISIBLE)
            lorieView.regenerate()

            // We should recover connection in the case if file descriptor for some reason was broken...
            if (!connected) tryConnect()
            if (connected) lorieView.setPointerIcon(
                PointerIcon.getSystemIcon(
                    this,
                    PointerIcon.TYPE_NULL
                )
            )
        }
    }

    private fun checkXEvents() {
        lorieView.handleXEvents()
        handler.postDelayed({ checkXEvents() }, 300)
    }

    companion object {
        const val ACTION_STOP = "com.micewine.emu.ACTION_STOP"
        private const val KEY_BACK = 158
        var handler = Handler(Looper.getMainLooper())

        @JvmStatic
        @SuppressLint("StaticFieldLeak")
        lateinit var instance: EmulationActivity private set
    }
}
