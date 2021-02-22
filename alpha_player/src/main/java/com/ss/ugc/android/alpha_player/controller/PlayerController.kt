package com.ss.ugc.android.alpha_player.controller

import android.arch.lifecycle.Lifecycle
import android.arch.lifecycle.LifecycleObserver
import android.arch.lifecycle.LifecycleOwner
import android.arch.lifecycle.OnLifecycleEvent
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.Process
import android.support.annotation.WorkerThread
import android.text.TextUtils
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import com.ss.ugc.android.alpha_player.IMonitor
import com.ss.ugc.android.alpha_player.IPlayerAction
import com.ss.ugc.android.alpha_player.model.AlphaVideoViewType
import com.ss.ugc.android.alpha_player.model.Configuration
import com.ss.ugc.android.alpha_player.model.DataSource
import com.ss.ugc.android.alpha_player.player.DefaultSystemPlayer
import com.ss.ugc.android.alpha_player.player.IMediaPlayer
import com.ss.ugc.android.alpha_player.player.PlayerState
import com.ss.ugc.android.alpha_player.render.VideoRenderer
import com.ss.ugc.android.alpha_player.vap.AnimConfig
import com.ss.ugc.android.alpha_player.vap.FileContainer
import com.ss.ugc.android.alpha_player.vap.util.ALog
import com.ss.ugc.android.alpha_player.widget.AlphaVideoGLSurfaceView
import com.ss.ugc.android.alpha_player.widget.AlphaVideoGLTextureView
import com.ss.ugc.android.alpha_player.widget.IAlphaVideoView
import org.json.JSONObject
import java.io.File
import java.lang.Exception

/**
 * created by dengzhuoyao on 2020/07/08
 */
class PlayerController(
    val context: Context,
    owner: LifecycleOwner,
    val alphaVideoViewType: AlphaVideoViewType,
    mediaPlayer: IMediaPlayer
) : IPlayerControllerExt, LifecycleObserver, Handler.Callback {

    //data class HandlerHolder(var thread: HandlerThread?, var handler: Handler?)

    companion object {
        private const val TAG = "PlayerController"

        const val INIT_MEDIA_PLAYER: Int = 1
        const val SET_DATA_SOURCE: Int = 2
        const val START: Int = 3
        const val PAUSE: Int = 4
        const val RESUME: Int = 5
        const val STOP: Int = 6
        const val DESTROY: Int = 7
        const val SURFACE_PREPARED: Int = 8
        const val RESET: Int = 9

        fun get(configuration: Configuration, mediaPlayer: IMediaPlayer? = null): PlayerController {
            return PlayerController(
                configuration.context, configuration.lifecycleOwner,
                configuration.alphaVideoViewType,
                mediaPlayer ?: DefaultSystemPlayer()
            )
        }

        /*fun createThread(handlerHolder: HandlerHolder, name: String): Boolean {
            try {
                if (handlerHolder.thread == null || handlerHolder.thread?.isAlive == false) {
                    handlerHolder.thread = HandlerThread(name).apply {
                        start()
                        handlerHolder.handler = Handler(looper)
                    }
                }
                return true
            } catch (e: OutOfMemoryError) {
                ALog.e(TAG, "createThread OOM", e)
            }
            return false
        }

        fun quitSafely(thread: HandlerThread?): HandlerThread? {
            thread?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                    thread.quitSafely()
                } else {
                    thread.quit()
                }
            }
            return null
        }*/
    }

    /* private val renderThread = HandlerHolder(null, null)

     fun prepareThread(): Boolean {
         return createThread(renderThread, "anim_render_thread")
     }

     private fun destroyThread() {
         ALog.i(TAG, "destroyThread")
         renderThread.handler?.removeCallbacksAndMessages(null)
         renderThread.thread = quitSafely(renderThread.thread)
         renderThread.handler = null
     }*/

    private var suspendDataSource: DataSource? = null
    var isPlaying: Boolean = false
    var playerState = PlayerState.NOT_PREPARED
    var mMonitor: IMonitor? = null
    var mPlayerAction: IPlayerAction? = null
    var mediaPlayer: IMediaPlayer
    lateinit var alphaVideoView: IAlphaVideoView

    var workHandler: Handler? = null
    val mainHandler: Handler = Handler(Looper.getMainLooper())
    var playThread: HandlerThread? = null

    private val mPreparedListener = object : IMediaPlayer.OnPreparedListener {
        override fun onPrepared() {
            sendMessage(getMessage(START, null))
        }
    }

    private val mErrorListener = object : IMediaPlayer.OnErrorListener {
        override fun onError(what: Int, extra: Int, desc: String) {
            monitor(false, what, extra, "mediaPlayer error, info: $desc")
            emitEndSignal()
        }
    }

    init {
        this.mediaPlayer = mediaPlayer
        init(owner)
        initAlphaView()
        initMediaPlayer()
    }

    private fun init(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(this)
        playThread = HandlerThread("alpha-play-thread", Process.THREAD_PRIORITY_BACKGROUND)
        playThread!!.start()
        workHandler = Handler(playThread!!.looper, this)
    }

    private fun initAlphaView() {
        alphaVideoView = when (alphaVideoViewType) {
            AlphaVideoViewType.GL_SURFACE_VIEW -> AlphaVideoGLSurfaceView(context, null)
            AlphaVideoViewType.GL_TEXTURE_VIEW -> AlphaVideoGLTextureView(context, null)
        }
        alphaVideoView.let {
            val layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            it.setLayoutParams(layoutParams)
            it.setPlayerController(this)
            it.setVideoRenderer(VideoRenderer(it))
        }
    }

    private fun initMediaPlayer() {
        sendMessage(getMessage(INIT_MEDIA_PLAYER, null))
    }

    override fun setPlayerAction(playerAction: IPlayerAction) {
        this.mPlayerAction = playerAction
    }

    override fun setMonitor(monitor: IMonitor) {
        this.mMonitor = monitor
    }

    override fun setVisibility(visibility: Int) {
        alphaVideoView.setVisibility(visibility)
        if (visibility == View.VISIBLE) {
            alphaVideoView.bringToFront()
        }
    }

    override fun attachAlphaView(parentView: ViewGroup) {
        alphaVideoView.addParentView(parentView)
    }

    override fun detachAlphaView(parentView: ViewGroup) {
        alphaVideoView.removeParentView(parentView)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun onPause() {
        pause()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun onResume() {
        resume()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onStop() {
        stop()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroy() {
        release()
    }

    private fun sendMessage(msg: Message) {
        playThread?.let {
            if (it.isAlive && !it.isInterrupted) {
                when (workHandler) {
                    null -> workHandler = Handler(it.looper, this)
                }
                workHandler!!.sendMessageDelayed(msg, 0)
            }
        }
    }

    private fun getMessage(what: Int, obj: Any?): Message {
        val message = Message.obtain()
        message.what = what
        message.obj = obj
        return message
    }

    override fun surfacePrepared(surface: Surface) {
        sendMessage(getMessage(SURFACE_PREPARED, surface))
    }

    override fun start(dataSource: DataSource) {
        if (dataSource.isValid()) {
            setVisibility(View.VISIBLE)
            sendMessage(getMessage(SET_DATA_SOURCE, dataSource))
        } else {
            emitEndSignal()
            monitor(false, errorInfo = "dataSource is invalid!")
        }
    }

    override fun pause() {
        sendMessage(getMessage(PAUSE, null))
    }

    override fun resume() {
        sendMessage(getMessage(RESUME, null))
    }

    override fun stop() {
        sendMessage(getMessage(STOP, null))
    }

    override fun reset() {
        sendMessage(getMessage(RESET, null))
    }

    override fun release() {
        sendMessage(getMessage(DESTROY, null))
    }

    override fun getView(): View {
        return alphaVideoView.getView()
    }

    override fun getPlayerType(): String {
        return mediaPlayer.getPlayerType()
    }

    @WorkerThread
    private fun initPlayer() {
        try {
            mediaPlayer.initMediaPlayer()
        } catch (e: Exception) {
            mediaPlayer = DefaultSystemPlayer()
            mediaPlayer.initMediaPlayer()
        }
        mediaPlayer.setScreenOnWhilePlaying(true)
        mediaPlayer.setLooping(false)

        mediaPlayer.setOnFirstFrameListener(object : IMediaPlayer.OnFirstFrameListener {
            override fun onFirstFrame() {
                alphaVideoView.onFirstFrame()
            }
        })
        mediaPlayer.setOnCompletionListener(object : IMediaPlayer.OnCompletionListener {
            override fun onCompletion() {
                alphaVideoView.onCompletion()
                playerState = PlayerState.PAUSED
                monitor(true, errorInfo = "")
                emitEndSignal()
            }
        })
    }

    @WorkerThread
    private fun setDataSource(dataSource: DataSource) {
        try {
            setVideoFromFile(dataSource)
        } catch (e: Exception) {
            e.printStackTrace()
            monitor(
                false,
                errorInfo = "alphaVideoView set dataSource failure: " + Log.getStackTraceString(e)
            )
            emitEndSignal()
        }
    }

    @WorkerThread
    private fun setVideoFromFile(dataSource: DataSource) {
        mediaPlayer.reset()
        playerState = PlayerState.NOT_PREPARED
        val orientation = context.resources.configuration.orientation

        val dataPath = dataSource.getPath(orientation)
        val scaleType = dataSource.getScaleType(orientation)
        if (TextUtils.isEmpty(dataPath) || !File(dataPath).exists()) {
            monitor(false, errorInfo = "dataPath is empty or File is not exists. path = $dataPath")
            emitEndSignal()
            return
        }

        //todo 从mp4中解析出json配置 在线程中解析配置
        //val fileContainer = FileContainer(File(dataPath))
        val json =
            "{\"info\":{\"v\":2,\"f\":240,\"w\":672,\"h\":1504,\"videoW\":1104,\"videoH\":1504,\"orien\":0,\"fps\":20,\"isVapx\":1,\"aFrame\":[684,4,336,752],\"rgbFrame\":[4,0,672,1504]},\"src\":[{\"srcId\":\"1\",\"srcType\":\"txt\",\"loadType\":\"local\", \"srcTag\":\"[sTxt1]\",\"color\":\"#FFF18D\",\"style\":\"b\",\"w\":336,\"h\":44,\"fitType\":\"fitXY\"},{\"srcId\":\"2\",\"srcType\":\"img\",\"loadType\":\"net\", \"srcTag\":\"[sImg1]\",\"w\":66,\"h\":66,\"fitType\":\"fitXY\"}],\"frame\":[{\"i\":191,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":198,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":205,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":212,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":219,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":226,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":233,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":147,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[148,310,352,46],\"mFrame\":[684,764,352,46],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[289,207,70,70],\"mFrame\":[684,818,70,70],\"mt\":0}]},{\"i\":154,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":161,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":168,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":175,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":182,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":189,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":196,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":203,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":210,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":217,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":224,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":231,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":238,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":145,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[132,308,385,51],\"mFrame\":[684,764,385,51],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[286,195,76,77],\"mFrame\":[684,823,76,77],\"mt\":0}]},{\"i\":152,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":159,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":166,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":173,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":180,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":187,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":194,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":201,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":208,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":215,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":222,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":229,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":236,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":143,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[174,313,301,40],\"mFrame\":[684,764,301,40],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[295,225,59,60],\"mFrame\":[684,812,59,60],\"mt\":0}]},{\"i\":150,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":157,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":164,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":171,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":178,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":185,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":192,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":199,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":206,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":213,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":220,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":227,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":234,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":141,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[273,325,101,14],\"mFrame\":[684,764,101,14],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[314,296,20,21],\"mFrame\":[684,786,20,21],\"mt\":0}]},{\"i\":148,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":155,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":162,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":169,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":176,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":183,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":190,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":197,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":204,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":211,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":218,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":225,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":232,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":146,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[140,309,369,49],\"mFrame\":[684,764,369,49],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[288,201,72,73],\"mFrame\":[684,821,72,73],\"mt\":0}]},{\"i\":153,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":160,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":167,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":174,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":181,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":188,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":195,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":202,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":209,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":216,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":223,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":230,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":237,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":144,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[123,307,403,53],\"mFrame\":[684,764,403,53],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[285,189,79,80],\"mFrame\":[684,825,79,80],\"mt\":0}]},{\"i\":151,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":158,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":165,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":172,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":179,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":186,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":193,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":200,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":207,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":214,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":221,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":228,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":235,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":142,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[224,319,200,27],\"mFrame\":[684,764,200,27],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[304,260,39,40],\"mFrame\":[684,799,39,40],\"mt\":0}]},{\"i\":149,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":156,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":163,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":170,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":177,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]},{\"i\":184,\"obj\":[{\"srcId\":\"1\",\"z\":0,\"frame\":[157,311,335,44],\"mFrame\":[684,764,335,44],\"mt\":0},{\"srcId\":\"2\",\"z\":1,\"frame\":[292,213,65,66],\"mFrame\":[684,816,65,66],\"mt\":0}]}]}"
        val jsonObj = JSONObject(json)
        val config = AnimConfig()
        val parse = config.parse(jsonObj)
        ALog.d("dq-av", "config parsed $parse")

        scaleType?.let {
            alphaVideoView.setScaleType(it)
        }
        alphaVideoView.setAnimConfig(config)

        mediaPlayer.setLooping(dataSource.isLooping)
        mediaPlayer.setDataSource(dataPath)
        if (alphaVideoView.isSurfaceCreated()) {
            prepareAsync()
        } else {
            suspendDataSource = dataSource
        }
    }

    @WorkerThread
    private fun handleSuspendedEvent() {
        suspendDataSource?.let {
            setVideoFromFile(it)
        }
        suspendDataSource = null
    }


    @WorkerThread
    private fun prepareAsync() {
        mediaPlayer.let {
            if (playerState == PlayerState.NOT_PREPARED || playerState == PlayerState.STOPPED) {
                it.setOnPreparedListener(mPreparedListener)
                it.setOnErrorListener(mErrorListener)
                it.prepareAsync()
            }
        }
    }

    @WorkerThread
    private fun startPlay() {
        when (playerState) {
            PlayerState.PREPARED -> {
                mediaPlayer.start()
                isPlaying = true
                playerState = PlayerState.STARTED
                mainHandler.post {
                    mPlayerAction?.startAction()
                }
            }
            PlayerState.PAUSED -> {
                mediaPlayer.start()
                playerState = PlayerState.STARTED
            }
            PlayerState.NOT_PREPARED, PlayerState.STOPPED -> {
                try {
                    prepareAsync()
                } catch (e: Exception) {
                    e.printStackTrace()
                    monitor(false, errorInfo = "prepare and start MediaPlayer failure!")
                    emitEndSignal()
                }
            }
        }
    }

    @WorkerThread
    private fun parseVideoSize() {
        val videoInfo = mediaPlayer.getVideoInfo()
        alphaVideoView.measureInternal(
            (videoInfo.videoWidth / 2).toFloat(),
            videoInfo.videoHeight.toFloat()
        )

        val scaleType = alphaVideoView.getScaleType()
        mainHandler.post {
            mPlayerAction?.onVideoSizeChanged(
                videoInfo.videoWidth / 2,
                videoInfo.videoHeight,
                scaleType
            )
        }
    }

    override fun handleMessage(msg: Message?): Boolean {
        msg?.let {
            when (msg.what) {
                INIT_MEDIA_PLAYER -> {
                    initPlayer()
                }
                SURFACE_PREPARED -> {
                    val surface = msg.obj as Surface
                    mediaPlayer.setSurface(surface)
                    handleSuspendedEvent()
                }
                SET_DATA_SOURCE -> {
                    val dataSource = msg.obj as DataSource
                    setDataSource(dataSource)
                }
                START -> {
                    try {
                        parseVideoSize()
                        playerState = PlayerState.PREPARED
                        startPlay()
                    } catch (e: Exception) {
                        monitor(
                            false,
                            errorInfo = "start video failure: " + Log.getStackTraceString(e)
                        )
                        emitEndSignal()
                    }
                }
                PAUSE -> {
                    when (playerState) {
                        PlayerState.STARTED -> {
                            mediaPlayer.pause()
                            playerState = PlayerState.PAUSED
                        }
                        else -> {
                        }
                    }
                }
                RESUME -> {
                    if (isPlaying) {
                        startPlay()
                    } else {
                    }
                }
                STOP -> {
                    when (playerState) {
                        PlayerState.STARTED, PlayerState.PAUSED -> {
                            mediaPlayer.pause()
                            playerState = PlayerState.PAUSED
                        }
                        else -> {
                        }
                    }
                }
                DESTROY -> {
                    alphaVideoView.onPause()
                    if (playerState == PlayerState.STARTED) {
                        mediaPlayer.pause()
                        playerState = PlayerState.PAUSED
                    }
                    if (playerState == PlayerState.PAUSED) {
                        mediaPlayer.stop()
                        playerState = PlayerState.STOPPED
                    }
                    mediaPlayer.release()
                    alphaVideoView.release()
                    playerState = PlayerState.RELEASE

                    playThread?.let {
                        it.quit()
                        it.interrupt()
                    }
                }
                RESET -> {
                    mediaPlayer.reset()
                    playerState = PlayerState.NOT_PREPARED
                    isPlaying = false
                }
                else -> {
                }
            }
        }
        return true
    }

    private fun emitEndSignal() {
        isPlaying = false
        mainHandler.post {
            mPlayerAction?.endAction()
        }
    }

    private fun monitor(state: Boolean, what: Int = 0, extra: Int = 0, errorInfo: String) {
        mMonitor?.monitor(state, getPlayerType(), what, extra, errorInfo)
    }
}