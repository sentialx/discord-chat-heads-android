package com.sential.discordbubbles.chatheads

import android.content.Context
import android.graphics.PixelFormat
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.VelocityTracker
import com.facebook.rebound.Spring
import com.facebook.rebound.SimpleSpringListener
import com.facebook.rebound.SpringChain
import com.sential.discordbubbles.client.GuildInfo
import com.sential.discordbubbles.utils.*
import java.util.*
import kotlin.math.*

class ChatHeads(context: Context) : View.OnTouchListener, FrameLayout(context) {
    companion object {
        val CHAT_HEAD_OUT_OF_SCREEN_X: Int = dpToPx(10f)
        val CHAT_HEAD_SIZE: Int = dpToPx(62f)
        val CHAT_HEAD_PADDING: Int = dpToPx(6f)
        val CHAT_HEAD_EXPANDED_PADDING: Int = dpToPx(4f)
        val CHAT_HEAD_EXPANDED_MARGIN_TOP: Float = dpToPx(6f).toFloat()
        val CLOSE_SIZE = dpToPx(64f)
        val CLOSE_CAPTURE_DISTANCE = dpToPx(100f)
        val CLOSE_ADDITIONAL_SIZE = dpToPx(24f)

        const val CHAT_HEAD_DRAG_TOLERANCE: Float = 20f

        fun distance(x1: Float, x2: Float, y1: Float, y2: Float): Float {
            return ((x1 - x2).pow(2) + (y1-y2).pow(2))
        }
    }

    var chatHeads = ArrayList<ChatHead>()

    var wasMoving = false
    var closeCaptured = false

    private var movingOutOfClose = false

    private var initialX = 0.0f
    private var initialY = 0.0f

    private var initialTouchX = 0.0f
    private var initialTouchY = 0.0f

    private var initialVelocityX = 0.0
    private var initialVelocityY = 0.0

    private var lastY = 0.0

    private var moving = false
    private var toggled = false
    private var motionTrackerUpdated = false
    private var collapsing = false
    private var blockAnim = false

    private var horizontalSpringChain: SpringChain? = null
    private var verticalSpringChain: SpringChain? = null

    private var isOnRight = false

    private var velocityTracker: VelocityTracker? = null

    private var motionTracker = LinearLayout(context)

    private var detectedOutOfBounds = false

    var showContentRunnable: Runnable? = null

    var topChatHead: ChatHead? = null
    var activeChatHead: ChatHead? = null

    var content = Content(context)
    private var close = Close(this)

    private var motionTrackerParams = WindowManager.LayoutParams(
        CHAT_HEAD_SIZE,
        CHAT_HEAD_SIZE + 16,
        getOverlayFlag(),
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    )

    private var params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        getOverlayFlag(),
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    )

    init {
        params.gravity = Gravity.START or Gravity.TOP
        params.dimAmount = 0.7f

        motionTrackerParams.gravity = Gravity.START or Gravity.TOP

        OverlayService.instance.windowManager.addView(motionTracker, motionTrackerParams)
        OverlayService.instance.windowManager.addView(this, params)
        this.addView(content)

        isFocusableInTouchMode = true

        motionTracker.setOnTouchListener(this)

        setOnTouchListener{ v, event ->
            v.performClick()

            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    if (v == this) {
                        collapse()
                    }
                }

            }

            return@setOnTouchListener false
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            collapse()
            true
        } else super.dispatchKeyEvent(event)
    }

    fun setTop(chatHead: ChatHead?) {
        destroySpringChains()

        if (chatHead == null) {
            topChatHead = null
            return
        }

        chatHeads[chatHeads.indexOf(chatHead)] = chatHeads[0]
        chatHeads[0] = chatHead

        topChatHead = chatHead

        resetSpringChains()
    }

    private fun destroySpringChains() {
        if (horizontalSpringChain != null) {
            for (spring in horizontalSpringChain!!.allSprings) {
                spring.destroy()
            }
        }

        if (verticalSpringChain != null) {
            for (spring in verticalSpringChain!!.allSprings) {
                spring.destroy()
            }
        }

        verticalSpringChain = null
        horizontalSpringChain = null
    }


    private fun resetSpringChains() {
        destroySpringChains()

        horizontalSpringChain = SpringChain.create(0, 0, 200, 15)
        verticalSpringChain = SpringChain.create(0, 0, 200, 15)

        chatHeads.forEachIndexed { index, element ->
            element.z = (chatHeads.size - 1 - index).toFloat()

            if (index == 0) {
                horizontalSpringChain!!.addSpring(object : SimpleSpringListener() { })
                verticalSpringChain!!.addSpring(object : SimpleSpringListener() { })

                horizontalSpringChain!!.setControlSpringIndex(index)
                verticalSpringChain!!.setControlSpringIndex(index)
            } else {
                horizontalSpringChain!!.addSpring(object : SimpleSpringListener() {
                    override fun onSpringUpdate(spring: Spring?) {
                        if (!toggled && !blockAnim) {
                            if (collapsing) {
                                element.springX.endValue = spring!!.endValue + index * CHAT_HEAD_PADDING * if (isOnRight) 1 else -1
                            } else {
                                element.springX.currentValue = spring!!.currentValue + index * CHAT_HEAD_PADDING * if (isOnRight) 1 else -1
                            }
                        }
                    }
                })
                verticalSpringChain!!.addSpring(object : SimpleSpringListener() {
                    override fun onSpringUpdate(spring: Spring?) {
                        if (!toggled && !blockAnim) {
                            element.springY.currentValue = spring!!.currentValue
                        }
                    }
                })
            }
        }
    }

    fun add(guildInfo: GuildInfo): ChatHead {
        var chatHead = chatHeads.find { it.guildInfo.id == guildInfo.id }

        if (chatHead != null) {
            return chatHead
        }

        chatHeads.forEach {
            it.visibility = View.VISIBLE
        }

        chatHead = ChatHead(this, guildInfo)
        chatHeads.add(chatHead)

        var lx = -CHAT_HEAD_OUT_OF_SCREEN_X.toDouble()
        var ly = 0.0

        if (topChatHead != null) {
            lx = topChatHead!!.springX.currentValue
            ly = topChatHead!!.springY.currentValue
        }

        if (!toggled) {
            setTop(chatHead)

            blockAnim = true

            chatHeads.forEachIndexed { index, element ->
                element.springX.currentValue = lx + index * CHAT_HEAD_PADDING * if (isOnRight) 1 else -1
                element.springY.currentValue = ly
            }

            motionTrackerParams.x = chatHead.springX.currentValue.toInt()
            motionTrackerParams.y = chatHead.springY.currentValue.toInt()
            motionTrackerParams.flags = motionTrackerParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()

            OverlayService.instance.windowManager.updateViewLayout(motionTracker, motionTrackerParams)
        } else {
            setTop(topChatHead)
            rearrangeExpanded(false)
        }

        return chatHead
    }

    fun collapse() {
        toggled = false
        collapsing = true

        val metrics = getScreenSize()

        val newX =  if (isOnRight) metrics.widthPixels - topChatHead!!.width + CHAT_HEAD_OUT_OF_SCREEN_X.toDouble() else -CHAT_HEAD_OUT_OF_SCREEN_X.toDouble()
        val newY = initialY.toDouble()

        topChatHead!!.springX.endValue = newX
        topChatHead!!.springY.endValue = newY

        activeChatHead = null

        content.hideContent()

        motionTrackerParams.flags = motionTrackerParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        OverlayService.instance.windowManager.updateViewLayout(motionTracker, motionTrackerParams)

        params.flags = (params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) and
                WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv() and
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv() or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        OverlayService.instance.windowManager.updateViewLayout(this, params)
    }

    fun updateActiveContent() {
        if (activeChatHead == null) return
        content.setInfo(activeChatHead!!)
    }

    private fun onClose() {
        close.hide()

        if (closeCaptured) {
            postDelayed({
                removeAll()
            }, 300)
        }
    }

    fun removeAll() {
        chatHeads.forEach {
            remove(it)
        }
    }

    fun remove(chatHead: ChatHead?) {
        if (chatHead == null || chatHeads.size == 0) return

        if (chatHeads.size == 1) {
            setTop(null)
        } else if (topChatHead == chatHead) {
            setTop(chatHeads[1])
        }

        this.removeView(chatHead)
        chatHeads.remove(chatHead)
    }

    fun rearrangeExpanded(animation: Boolean = true) {
        val metrics = getScreenSize()

        chatHeads.forEachIndexed { index, it ->
            it.springX.springConfig = SpringConfigs.NOT_DRAGGING
            it.springY.springConfig = SpringConfigs.NOT_DRAGGING

            val x = metrics.widthPixels - topChatHead!!.width.toDouble() - index * (it.params.width + CHAT_HEAD_EXPANDED_PADDING).toDouble()
            val y = CHAT_HEAD_EXPANDED_MARGIN_TOP.toDouble()

            if (animation) {
                it.springY.endValue = y
                it.springX.endValue = x
            } else {
                it.springY.currentValue = y
                it.springX.currentValue = x
            }
        }
    }

    fun onChatHeadSpringUpdate(chatHead: ChatHead, spring: Spring, totalVelocity: Int) {
        val metrics = getScreenSize()

        if (topChatHead != null && chatHead == topChatHead!!) {
            if (horizontalSpringChain != null && spring == chatHead.springX) {
                horizontalSpringChain!!.controlSpring.currentValue = spring.currentValue
            }

            if (verticalSpringChain != null && spring == chatHead.springY) {
                verticalSpringChain!!.controlSpring.currentValue = spring.currentValue
            }
        }

        var tmpChatHead: ChatHead? = null
        if (collapsing) tmpChatHead = topChatHead!!
        else if (chatHead == activeChatHead) tmpChatHead = chatHead

        if (tmpChatHead != null) {
            content.x = tmpChatHead.springX.currentValue.toFloat() - metrics.widthPixels.toFloat() + chatHeads.indexOf(tmpChatHead) * (tmpChatHead.width + CHAT_HEAD_EXPANDED_PADDING) + tmpChatHead.width
            content.y = tmpChatHead.springY.currentValue.toFloat() - CHAT_HEAD_EXPANDED_MARGIN_TOP
            content.pivotX = metrics.widthPixels.toFloat() - chatHead.width / 2 - chatHeads.indexOf(tmpChatHead) * (tmpChatHead.width + CHAT_HEAD_EXPANDED_PADDING)
        }

        content.pivotY = chatHead.height.toFloat()

        if (topChatHead != null && !moving && distance(close.x, topChatHead!!.springX.currentValue.toFloat(), close.y, topChatHead!!.springY.currentValue.toFloat()) < CLOSE_CAPTURE_DISTANCE * CLOSE_CAPTURE_DISTANCE && !closeCaptured && close.visibility == View.VISIBLE) {
            topChatHead!!.springX.springConfig = SpringConfigs.CAPTURING
            topChatHead!!.springY.springConfig = SpringConfigs.CAPTURING

            topChatHead!!.springX.endValue = close.springX.endValue
            topChatHead!!.springY.endValue = close.springY.endValue

            onClose()

            closeCaptured = true
        }

        if (wasMoving) {
            motionTrackerParams.x = if (isOnRight) metrics.widthPixels - chatHead.width else 0

            lastY = chatHead.springY.currentValue

            if (!detectedOutOfBounds) {
                if (chatHead.springY.currentValue < 0) {
                    chatHead.springY.endValue = 0.0
                    detectedOutOfBounds = true
                } else if (chatHead.springY.currentValue > metrics.heightPixels) {
                    chatHead.springY.endValue = metrics.heightPixels - CHAT_HEAD_SIZE.toDouble()
                    detectedOutOfBounds = true
                }
            }

            if (!moving) {
                if (spring === chatHead.springX) {
                    val xPosition = chatHead.springX.currentValue
                    if (xPosition + chatHead.width > metrics.widthPixels && chatHead.springX.velocity > 0) {
                        val newPos = metrics.widthPixels - chatHead.width + CHAT_HEAD_OUT_OF_SCREEN_X
                        chatHead.springX.springConfig = SpringConfigs.NOT_DRAGGING
                        chatHead.springX.endValue = newPos.toDouble()
                        isOnRight = true
                    } else if (xPosition < 0 && chatHead.springX.velocity < 0) {
                        chatHead.springX.springConfig = SpringConfigs.NOT_DRAGGING
                        chatHead.springX.endValue = -CHAT_HEAD_OUT_OF_SCREEN_X.toDouble()
                        isOnRight = false
                    }
                } else if (spring === chatHead.springY) {
                    val yPosition = chatHead.springY.currentValue
                    if (yPosition + chatHead.height > metrics.heightPixels && chatHead.springY.velocity > 0) {
                        chatHead.springY.springConfig = SpringConfigs.NOT_DRAGGING
                        chatHead.springY.endValue = metrics.heightPixels - chatHead.height.toDouble() - dpToPx(25f)
                    } else if (yPosition < 0 && chatHead.springY.velocity < 0) {
                        chatHead.springY.springConfig = SpringConfigs.NOT_DRAGGING
                        chatHead.springY.endValue = 0.0
                    }
                }
            }

            if (Math.abs(totalVelocity) % 10 == 0 && !moving && topChatHead != null) {
                motionTrackerParams.y = topChatHead!!.springY.currentValue.toInt()

                OverlayService.instance.windowManager.updateViewLayout(motionTracker, motionTrackerParams)
            }
        }
    }

    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        val metrics = getScreenSize()

        if (topChatHead == null) return true

        when (event!!.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = topChatHead!!.springX.currentValue.toFloat()
                initialY = topChatHead!!.springY.currentValue.toFloat()
                initialTouchX = event.rawX
                initialTouchY = event.rawY

                wasMoving = false
                collapsing = false
                blockAnim = false
                detectedOutOfBounds = false

                close.show()

                topChatHead!!.scaleX = 0.92f
                topChatHead!!.scaleY = 0.92f

                topChatHead!!.springX.springConfig = SpringConfigs.DRAGGING
                topChatHead!!.springY.springConfig = SpringConfigs.DRAGGING

                topChatHead!!.springX.setAtRest()
                topChatHead!!.springY.setAtRest()

                motionTrackerUpdated = false

                if (velocityTracker == null) {
                    velocityTracker = VelocityTracker.obtain()
                } else {
                    velocityTracker?.clear()
                }

                velocityTracker?.addMovement(event)
            }
            MotionEvent.ACTION_UP -> {
                if (moving) wasMoving = true

                onClose()

                if (closeCaptured) return true

                if (!moving) {
                    if (!toggled) {
                        toggled = true

                        rearrangeExpanded()

                        motionTrackerParams.flags = motionTrackerParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        OverlayService.instance.windowManager.updateViewLayout(motionTracker, motionTrackerParams)

                        params.flags = (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()) or WindowManager.LayoutParams.FLAG_DIM_BEHIND and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv() and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                        OverlayService.instance.windowManager.updateViewLayout(this, params)

                        activeChatHead = topChatHead

                        updateActiveContent()

                        handler.removeCallbacks(showContentRunnable)

                        showContentRunnable = Runnable {
                            content.showContent()
                        }

                        handler.postDelayed(showContentRunnable, 200)
                    }
                } else if (!toggled) {
                    moving = false

                    var xVelocity = velocityTracker!!.xVelocity.toDouble()
                    var yVelocity = velocityTracker!!.yVelocity.toDouble()
                    var maxVelocityX = 0.0

                    velocityTracker?.recycle()
                    velocityTracker = null

                    if (xVelocity < -3500) {
                        val newVelocity = ((-topChatHead!!.springX.currentValue -  CHAT_HEAD_OUT_OF_SCREEN_X) * SpringConfigs.DRAGGING.friction)
                        maxVelocityX = newVelocity - 5000
                        if (xVelocity > maxVelocityX)
                            xVelocity = newVelocity - 500
                    } else if (xVelocity > 3500) {
                        val newVelocity = ((metrics.widthPixels - topChatHead!!.springX.currentValue - topChatHead!!.width + CHAT_HEAD_OUT_OF_SCREEN_X) * SpringConfigs.DRAGGING.friction)
                        maxVelocityX = newVelocity + 5000
                        if (maxVelocityX > xVelocity)
                            xVelocity = newVelocity + 500
                    } else if (yVelocity > 20 || yVelocity < -20) {
                        topChatHead!!.springX.springConfig = SpringConfigs.NOT_DRAGGING

                        if (topChatHead!!.x >= metrics.widthPixels / 2) {
                            topChatHead!!.springX.endValue = metrics.widthPixels - topChatHead!!.width + CHAT_HEAD_OUT_OF_SCREEN_X.toDouble()
                            isOnRight = true
                        } else {
                            topChatHead!!.springX.endValue = -CHAT_HEAD_OUT_OF_SCREEN_X.toDouble()

                            isOnRight = false
                        }
                    } else {
                        topChatHead!!.springX.springConfig = SpringConfigs.NOT_DRAGGING
                        topChatHead!!.springY.springConfig = SpringConfigs.NOT_DRAGGING

                        if (topChatHead!!.x >= metrics.widthPixels / 2) {
                            topChatHead!!.springX.endValue = metrics.widthPixels - topChatHead!!.width +
                                    CHAT_HEAD_OUT_OF_SCREEN_X.toDouble()
                            topChatHead!!.springY.endValue = topChatHead!!.y.toDouble()

                            isOnRight = true
                        } else {
                            topChatHead!!.springX.endValue = -CHAT_HEAD_OUT_OF_SCREEN_X.toDouble()
                            topChatHead!!.springY.endValue = topChatHead!!.y.toDouble()

                            isOnRight = false
                        }
                    }

                    xVelocity = if (xVelocity < 0) {
                        max(xVelocity - 1000.0, maxVelocityX)
                    } else {
                        min(xVelocity + 1000.0, maxVelocityX)
                    }

                    initialVelocityX = xVelocity
                    initialVelocityY = yVelocity

                    topChatHead!!.springX.velocity = xVelocity
                    topChatHead!!.springY.velocity = yVelocity
                }

                topChatHead!!.scaleX = 1f
                topChatHead!!.scaleY = 1f
            }
            MotionEvent.ACTION_MOVE -> {
                if (distance(initialTouchX, event.rawX, initialTouchY, event.rawY) > CHAT_HEAD_DRAG_TOLERANCE.pow(2)) {
                    moving = true
                }

                velocityTracker?.addMovement(event)

                if (moving) {
                    close.springX.endValue = (metrics.widthPixels / 2) + (((event.rawX + topChatHead!!.width / 2) / 7) - metrics.widthPixels / 2 / 7) - close.width.toDouble() / 2
                    close.springY.endValue = (metrics.heightPixels - CLOSE_SIZE) + max(((event.rawY + close.height / 2) / 10) - metrics.heightPixels / 10, -dpToPx(30f).toFloat()) - dpToPx(60f).toDouble()

                    if (distance(close.x + close.width / 2, event.rawX, close.y + close.height / 2, event.rawY) < CLOSE_CAPTURE_DISTANCE * CLOSE_CAPTURE_DISTANCE) {
                        topChatHead!!.springX.springConfig = SpringConfigs.CAPTURING
                        topChatHead!!.springY.springConfig = SpringConfigs.CAPTURING

                        close.springScale.endValue = CLOSE_ADDITIONAL_SIZE.toDouble()

                        closeCaptured = true
                    } else if (closeCaptured) {
                        topChatHead!!.springX.springConfig = SpringConfigs.CAPTURING
                        topChatHead!!.springY.springConfig = SpringConfigs.CAPTURING

                        close.springScale.endValue = 0.0

                        topChatHead!!.springX.endValue = initialX + (event.rawX - initialTouchX).toDouble()
                        topChatHead!!.springY.endValue = initialY + (event.rawY - initialTouchY).toDouble()

                        closeCaptured = false

                        movingOutOfClose = true

                        postDelayed({
                            movingOutOfClose = false
                        }, 100)
                    } else if (!movingOutOfClose) {
                        topChatHead!!.springX.springConfig = SpringConfigs.DRAGGING
                        topChatHead!!.springY.springConfig = SpringConfigs.DRAGGING

                        topChatHead!!.springX.currentValue = initialX + (event.rawX - initialTouchX).toDouble()
                        topChatHead!!.springY.currentValue = initialY + (event.rawY - initialTouchY).toDouble()

                        velocityTracker?.computeCurrentVelocity(2000)
                    }
                }
            }
        }

        return true
    }
}