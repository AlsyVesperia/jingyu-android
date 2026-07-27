package com.example.chat

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 屏幕共享前台Service
 * 职责：持有MediaProjection，定时截屏为Bitmap，通过回调传递给ScreenShareManager
 * 注意：必须在收到MediaProjection Intent后通过静态方法 start() 启动
 */
class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "screen_capture"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "ScreenCaptureService"

        /** 截帧回调，在非UI线程调用 */
        var frameCallback: ((Bitmap) -> Unit)? = null

        /** 从外部启动Service */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var cleaningUp = false
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(
            TAG,
            "onStartCommand 被调用, resultCode=${
                intent?.getIntExtra(
                    "resultCode",
                    -1
                )
            }, data=${intent?.hasExtra("data")}"
        )
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "前台通知已显示")

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")
        if (resultCode == Activity.RESULT_OK && data != null) {
            startCapture(resultCode, data)
        } else {
            Log.e(TAG, "启动参数无效，resultCode=$resultCode, data=$data")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            Log.d(TAG, "准备开始截屏: ${width}x${height}, dpi=$density")

            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                Log.e(TAG, "mediaProjection 为 null，停止服务")
                stopSelf()
                return
            }

            mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    stopCapture()
                }
            }, null)

            imageReader =
                ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            Log.d(TAG, "ImageReader 创建完成")

            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )
            Log.d(TAG, "VirtualDisplay 创建完成")

            // 后台线程处理截帧
            handlerThread = HandlerThread("ScreenCaptureThread").also { it.start() }
            handler = Handler(handlerThread!!.looper)

            handler?.post(object : Runnable {
                override fun run() {
                    var image: android.media.Image? = null
                    try {
                        image = imageReader?.acquireLatestImage()
                        if (image != null) {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * width

                            val bitmap = Bitmap.createBitmap(
                                width + rowPadding / pixelStride,
                                height,
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.copyPixelsFromBuffer(buffer)
                            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                            bitmap.recycle()

                            // 【新增的检查部分】
                            if (cleaningUp || mediaProjection == null) {
                                cropped.recycle()
                                return  // ← 这里用 return，不要加标签
                            }
                            val callback = frameCallback
                            if (callback != null) callback(cropped) else cropped.recycle()
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "截帧异常", e)
                    } finally {
                        image?.close()
                    }
                    handler?.postDelayed(this, 500) // 2fps
                }
            })
            Log.d(TAG, "截帧线程已启动")
        } catch (e: Throwable) {
            Log.e(TAG, "startCapture 发生异常", e)
            stopSelf()
        }
    }

    private fun releaseCaptureResources() {
        if (cleaningUp) return
        cleaningUp = true
        try {
            handler?.removeCallbacksAndMessages(null)
            handler = null
            handlerThread?.quitSafely()
            handlerThread = null
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            val projection = mediaProjection
            mediaProjection = null
            runCatching { projection?.stop() }
            frameCallback = null
        } finally {
            cleaningUp = false
        }
    }

    private fun stopCapture() {
        releaseCaptureResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "屏幕共享",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "屏幕共享进行中"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("小鲸鱼正在观看屏幕")
            .setContentText("点击回到聊天")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        releaseCaptureResources()
        super.onDestroy()
    }
}