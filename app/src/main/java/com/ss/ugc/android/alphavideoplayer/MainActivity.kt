package com.ss.ugc.android.alphavideoplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Toast
import com.ss.ugc.android.alpha_player.IMonitor
import com.ss.ugc.android.alpha_player.IPlayerAction
import com.ss.ugc.android.alpha_player.model.ScaleType
import com.ss.ugc.android.alpha_player.vap.Resource
import com.ss.ugc.android.alpha_player.vap.inter.IFetchResource
import com.ss.ugc.android.alphavideoplayer.utils.PermissionUtils
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File

/**
 * created by dengzhuoyao on 2020/07/08
 */
class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    val basePath = Environment.getExternalStorageDirectory().absolutePath

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        PermissionUtils.verifyStoragePermissions(this)
        initVideoGiftView()
    }

    private fun initVideoGiftView() {
        video_gift_view.initPlayerController(this, this, playerAction, fetchResource, monitor)
        video_gift_view.post {
            video_gift_view.attachView()
            startPlay()
        }
    }

    private val playerAction = object : IPlayerAction {
        override fun onVideoSizeChanged(videoWidth: Int, videoHeight: Int, scaleType: ScaleType) {
            Log.i(
                TAG,
                "call onVideoSizeChanged(), videoWidth = $videoWidth, videoHeight = $videoHeight, scaleType = $scaleType"
            )
        }

        override fun startAction() {
            Log.i(TAG, "call startAction()")
        }

        override fun endAction() {
            Log.i(TAG, "call endAction")
        }
    }

    private var head1Img = true

    private val fetchResource = object : IFetchResource {
        /**
         * 获取图片资源
         * 无论图片是否获取成功都必须回调 result 否则会无限等待资源
         */
        override fun fetchImage(resource: Resource, result: (Bitmap?) -> Unit) {
            /**
             * srcTag是素材中的一个标记，在制作素材时定义
             * 解析时由业务读取tag决定需要播放的内容是什么
             * 比如：一个素材里需要显示多个头像，则需要定义多个不同的tag，表示不同位置，需要显示不同的头像，文字类似
             */
            val srcTag = resource.tag

            if (srcTag == "[sImg1]") {//if (srcTag == "[tag1]") { // 此tag是已经写入到动画配置中的tag
                val drawableId = if (head1Img) R.mipmap.dq else R.mipmap.dq
                head1Img = !head1Img
                val options = BitmapFactory.Options()
                options.inScaled = false
                result(BitmapFactory.decodeResource(resources, drawableId, options))
            } else {
                result(null)
            }
        }

        /**
         * 获取文字资源
         */
        override fun fetchText(resource: Resource, result: (String?) -> Unit) {
            //val str = "恭喜 No.${1000 + Random().nextInt(8999)} 杜小菜 升神"
            val str = "杜小菜升神"
            val srcTag = resource.tag

            if (srcTag == "[sTxt1]") { // 此tag是已经写入到动画配置中的tag
                //if (srcTag == "tag1") {
                result(str)
            } else {
                result(null)
            }
        }

        /**
         * 播放完毕后的资源回收
         */
        override fun releaseResource(resources: List<Resource>) {
            resources.forEach {
                it.bitmap?.recycle()
            }
        }
    }

    private val monitor = object : IMonitor {
        override fun monitor(
            state: Boolean,
            playType: String,
            what: Int,
            extra: Int,
            errorInfo: String
        ) {
            Log.i(
                TAG,
                "call monitor(), state: $state, playType = $playType, what = $what, extra = $extra, errorInfo = $errorInfo"
            )
        }
    }

    fun attachView(v: View) {
        video_gift_view.attachView()
    }

    fun detachView(v: View) {
        video_gift_view.detachView()
    }

    fun playGift(v: View) {
        startPlay()
    }

    private fun startPlay() {
        val testPath = getResourcePath()
        Log.i("dzy", "play gift file path : $testPath")
        if ("".equals(testPath)) {
            Toast.makeText(
                this,
                "please run 'gift_install.sh gift/demoRes' for load alphaVideo resource.",
                Toast.LENGTH_SHORT
            )
                .show()
        }
        video_gift_view.startVideoGift(testPath)
    }

    private fun getResourcePath(): String {
        val dirPath = basePath + File.separator + "alphaVideoGift" + File.separator
        val dirFile = File(dirPath)
        if (dirFile.exists() && dirFile.listFiles() != null && dirFile.listFiles().isNotEmpty()) {
            return dirFile.listFiles()[0].absolutePath
        }
        return ""
    }

    override fun onDestroy() {
        super.onDestroy()
        video_gift_view.releasePlayerController()
    }
}
