package com.ss.ugc.android.alpha_player.render

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.util.Log
import android.view.Surface
import com.ss.ugc.android.alpha_player.model.ScaleType
import com.ss.ugc.android.alpha_player.vap.*
import com.ss.ugc.android.alpha_player.vap.util.GlFloatArray
import com.ss.ugc.android.alpha_player.widget.IAlphaVideoView
import com.ss.ugc.android.alpha_player.vap.util.ShaderUtil
import com.ss.ugc.android.alpha_player.vap.util.VertexUtil
import java.lang.Exception
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * created by dengzhuoyao on 2020/07/07
 */
class VideoRenderer(val alphaVideoView: IAlphaVideoView) : IRender {

    private val TAG = "VideoRender"
    private val GL_TEXTURE_EXTERNAL_OES = 0x8D65

    /*private val FLOAT_SIZE_BYTES = 4
    private val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
    private val TRIANGLE_VERTICES_DATA_POS_OFFSET = 0
    private val TRIANGLE_VERTICES_DATA_UV_OFFSET = 3
    private var halfRightVerticeData = floatArrayOf(
        // X, Y, Z, U, V
        -1.0f, -1.0f, 0f, 0.5f, 0f,
        1.0f, -1.0f, 0f, 1f, 0f,
        -1.0f, 1.0f, 0f, 0.5f, 1f,
        1.0f, 1.0f, 0f, 1f, 1f
    )
    private var triangleVertices: FloatBuffer*/

    private val mVPMatrix = FloatArray(16)
    private val sTMatrix = FloatArray(16)
    private var programID: Int = 0
    private var textureID: Int = 0
    private var uMVPMatrixHandle: Int = 0
    private var uSTMatrixHandle: Int = 0

    //private var aPositionHandle: Int = 0
    //private var aTextureHandle: Int = 0
    private val canDraw = AtomicBoolean(false)
    private val updateSurface = AtomicBoolean(false)
    private lateinit var surfaceTexture: SurfaceTexture
    private var surfaceListener: IRender.SurfaceListener? = null
    private var scaleType = ScaleType.ScaleAspectFill

    //vap
    private val vertexArray = GlFloatArray()
    private val alphaArray = GlFloatArray()
    private val rgbArray = GlFloatArray()
    private val eglUtil: EGLUtil = EGLUtil()

    //private var programID = 0
    //private var genTexture = IntArray(1)
    private var uTextureLocation: Int = 0
    private var aPositionLocation: Int = 0
    private var aTextureAlphaLocation: Int = 0
    private var aTextureRgbLocation: Int = 0

    private var surfaceSizeChanged = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    init {
        /*triangleVertices = ByteBuffer.allocateDirect(halfRightVerticeData.size * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        triangleVertices.put(halfRightVerticeData).position(0)*/
        Matrix.setIdentityM(sTMatrix, 0)
    }

    override fun setScaleType(scaleType: ScaleType) {
        this.scaleType = scaleType
        // TODO: 2021/2/22  config传入
        val config = testConfig()
        initByConfig(config)
    }

    private fun testConfig(): AnimConfig {
        val config = AnimConfig()
        config.width = 672
        config.height = 1504
        config.videoWidth = 1104
        config.videoHeight = 1504

        config.rgbPointRect = PointRect(
            4,
            0,
            672,
            1504
        )
        config.alphaPointRect = PointRect(
            684,
            4,
            336,
            752
        )
        return config
    }

    private fun initByConfig(config: AnimConfig) {
        //AnimConfig(version=2, totalFrames=240, width=672, height=1504, videoWidth=1104, videoHeight=1504, orien=0, fps=20, isMix=true,
        // alphaPointRect=PointRect(x=684, y=4, w=336, h=752), rgbPointRect=PointRect(x=4, y=0, w=672, h=1504), isDefaultConfig=false)
        setVertexBuf(config)
        setTexCoords(config)
    }

    private fun setVertexBuf(config: AnimConfig) {
        vertexArray.setArray(
            VertexUtil.create(
                config.width,
                config.height,
                PointRect(0, 0, config.width, config.height),
                vertexArray.array
            )
        )
    }

    private fun setTexCoords(config: AnimConfig) {
        val alpha = TexCoordsUtil.create(
            config.videoWidth,
            config.videoHeight,
            config.alphaPointRect,
            alphaArray.array
        )
        val rgb = TexCoordsUtil.create(
            config.videoWidth,
            config.videoHeight,
            config.rgbPointRect,
            rgbArray.array
        )
        alphaArray.setArray(alpha)
        rgbArray.setArray(rgb)
    }

    override fun measureInternal(
        viewWidth: Float, viewHeight: Float,
        videoWidth: Float, videoHeight: Float
    ) {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return
        }

        /* halfRightVerticeData = TextureCropUtil.calculateHalfRightVerticeData(scaleType,
             viewWidth, viewHeight, videoWidth, videoHeight)
         triangleVertices = ByteBuffer.allocateDirect(halfRightVerticeData.size * FLOAT_SIZE_BYTES)
             .order(ByteOrder.nativeOrder()).asFloatBuffer()
         triangleVertices.put(halfRightVerticeData).position(0)*/
    }

    override fun setSurfaceListener(surfaceListener: IRender.SurfaceListener) {
        this.surfaceListener = surfaceListener
    }

    override fun onDrawFrame(glUnused: GL10) {
        if (updateSurface.compareAndSet(true, false)) {
            try {
                surfaceTexture.updateTexImage()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            surfaceTexture.getTransformMatrix(sTMatrix)
        }

        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
        if (!canDraw.get()) {
            GLES20.glFinish()
            return
        }

        draw()

        /*GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(programID)
        checkGlError("glUseProgram")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureID)

        triangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(
            aPositionHandle, 3, GLES20.GL_FLOAT, false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices
        )
        checkGlError("glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        checkGlError("glEnableVertexAttribArray aPositionHandle")

        triangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(
            aTextureHandle, 3, GLES20.GL_FLOAT, false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices
        )
        checkGlError("glVertexAttribPointer aTextureHandle")
        GLES20.glEnableVertexAttribArray(aTextureHandle)
        checkGlError("glEnableVertexAttribArray aTextureHandle")

        Matrix.setIdentityM(mVPMatrix, 0)
        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mVPMatrix, 0)
        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, sTMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")

        GLES20.glFinish()*/
    }


    private fun draw() {
        GLES20.glUseProgram(programID)
        // 设置顶点坐标
        vertexArray.setVertexAttribPointer(aPositionLocation)
        // 绑定纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureID)
        GLES20.glUniform1i(uTextureLocation, 0)

        // 设置纹理坐标
        // alpha 通道坐标
        alphaArray.setVertexAttribPointer(aTextureAlphaLocation)
        // rgb 通道坐标
        rgbArray.setVertexAttribPointer(aTextureRgbLocation)

        /*Matrix.setIdentityM(mVPMatrix, 0)
        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mVPMatrix, 0)
        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, sTMatrix, 0)*/
        checkGlError("setVertexAttribPointer")

        // draw
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")

    }

    override fun onSurfaceChanged(glUnused: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)

        surfaceWidth = width
        surfaceHeight = height
        updateViewPort(width, height)
    }


    /**
     * 显示区域大小变化
     */
    private fun updateViewPort(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        surfaceSizeChanged = true
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun onSurfaceCreated(glUnused: GL10, config: EGLConfig) {
        programID = ShaderUtil.createProgram(RenderConstant.VERTEX_SHADER, RenderConstant.FRAGMENT_SHADER)
        if (programID == 0) {
            return
        }
        uTextureLocation = GLES20.glGetUniformLocation(programID, "texture")
        checkGlError("glGetUniformLocation")
        if (uTextureLocation == -1) {
            throw RuntimeException("Could not get location for uTextureLocation")
        }
        aPositionLocation = GLES20.glGetAttribLocation(programID, "vPosition")
        aTextureAlphaLocation = GLES20.glGetAttribLocation(programID, "vTexCoordinateAlpha")
        aTextureRgbLocation = GLES20.glGetAttribLocation(programID, "vTexCoordinateRgb")

        //aPositionHandle = GLES20.glGetAttribLocation(programID, "aPosition")
        //aTextureHandle = GLES20.glGetAttribLocation(programID, "aTextureCoord")

        // TODO: 2021/2/22  Could not get attrib location for uSTMatrix
        /*uMVPMatrixHandle = GLES20.glGetUniformLocation(programID, "uMVPMatrix")
        uSTMatrixHandle = GLES20.glGetUniformLocation(programID, "uSTMatrix")
        checkGlError("glGetUniformLocation uSTMatrix")
        if (uSTMatrixHandle == -1) {
            throw RuntimeException("Could not get attrib location for uSTMatrix")
        }*/
        prepareSurface()
    }

    override fun onSurfaceDestroyed(gl: GL10?) {
        surfaceListener?.onSurfaceDestroyed()
        clearFrame()
    }

    fun clearFrame() {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        eglUtil.swapBuffers()
    }

    private fun prepareSurface() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)

        textureID = textures[0]
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureID)
        checkGlError("glBindTexture textureID")

        GLES20.glTexParameterf(
            GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_NEAREST.toFloat()
        )
        GLES20.glTexParameterf(
            GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR.toFloat()
        )

        surfaceTexture = SurfaceTexture(textureID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
            surfaceTexture.setDefaultBufferSize(
                alphaVideoView.getMeasuredWidth(),
                alphaVideoView.getMeasuredHeight()
            )
        }
        surfaceTexture.setOnFrameAvailableListener(this)

        val surface = Surface(this.surfaceTexture)
        surfaceListener?.onSurfacePrepared(surface)
        updateSurface.compareAndSet(true, false)
    }

    override fun onFrameAvailable(surface: SurfaceTexture) {
        updateSurface.compareAndSet(false, true)
        alphaVideoView.requestRender()

        surfaceListener?.onFrameAvailable(surface)
    }

    override fun onFirstFrame() {
        canDraw.compareAndSet(false, true)
        Log.i(TAG, "onFirstFrame:    canDraw = " + canDraw.get())
        alphaVideoView.requestRender()
    }

    override fun onCompletion() {
        canDraw.compareAndSet(true, false)
        Log.i(TAG, "onCompletion:   canDraw = " + canDraw.get())
        alphaVideoView.requestRender()
    }

    /**
     * load shader by OpenGL ES, if compile shader success, it will return shader handle,
     * else return 0.
     *
     * @param shaderType shader type, {@link GLES20.GL_VERTEX_SHADER} and
     * {@link GLES20.GL_FRAGMENT_SHADER}
     * @param source   shader source
     *
     * @return shaderID If compile shader success, it will return shader handle, else return 0.
     */
    private fun loadShader(shaderType: Int, source: String): Int {
        var shader = GLES20.glCreateShader(shaderType)
        if (shader != 0) {
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader $shaderType:")
                Log.e(TAG, GLES20.glGetShaderInfoLog(shader))
                GLES20.glDeleteShader(shader)
                shader = 0
            }
        }
        return shader
    }

    /**
     * create program with {@link vertex.glsl} and {@link frag.glsl}. If attach shader or link
     * program, it will return 0, else return program handle
     *
     * @return programID If link program success, it will return program handle, else return 0.
     */
    private fun createProgram(): Int {
        val vertexSource = RenderConstant.VERTEX_SHADER
        val fragmentSource = RenderConstant.FRAGMENT_SHADER
        //val vertexSource = ShaderUtil.loadFromAssetsFile("vertex.glsl", alphaVideoView.getView().resources)
        //val fragmentSource = ShaderUtil.loadFromAssetsFile("frag.glsl", alphaVideoView.getView().resources)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) {
            return 0
        }
        val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (pixelShader == 0) {
            return 0
        }
        var program = GLES20.glCreateProgram()
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader)
            checkGlError("glAttachShader")
            GLES20.glAttachShader(program, pixelShader)
            checkGlError("glAttachShader")
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link programID: ")
                Log.e(TAG, GLES20.glGetProgramInfoLog(program))
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }
        return program
    }

    private fun checkGlError(op: String) {
        val error: Int = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "$op: glError $error")
            // TODO: 2018/4/25 端监控 用于监控礼物播放成功状态
        }
    }
}