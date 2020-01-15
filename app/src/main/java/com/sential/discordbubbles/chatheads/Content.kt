package com.sential.discordbubbles.chatheads

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.*
import com.facebook.rebound.SimpleSpringListener
import com.facebook.rebound.Spring
import com.facebook.rebound.SpringSystem
import com.sential.discordbubbles.*
import com.sential.discordbubbles.utils.*
import kotlinx.android.synthetic.main.chat_head_content.view.*
import net.dv8tion.jda.api.entities.Message
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import net.dv8tion.jda.api.entities.ChannelType
import net.dv8tion.jda.api.entities.User

class ListenableMessage(val message: Message) {
    var onAvatarChange: ((it: Bitmap?) -> Unit)? = null
}

class Content(context: Context): LinearLayout(context) {
    private val springSystem = SpringSystem.create()
    private val scaleSpring = springSystem.createSpring()

    private var channelView: TextView
    private var hashTagView: TextView
    private var atView: TextView
    private var scrollView: ScrollView

    private var lastAuthorName: String? = null
    private var lastMessageGroup: View? = null

    private var messagesView: RelativeLayout

    private var avatarsCache = mutableMapOf<String, Bitmap?>()

    init {
        inflate(context, R.layout.chat_head_content, this)

        channelView = findViewById(R.id.channel)
        hashTagView = findViewById(R.id.hashtag)
        atView = findViewById(R.id.at)
        messagesView = findViewById(R.id.messages)
        scrollView = findViewById(R.id.scrollView)

        val editText: EditText = findViewById(R.id.editText)
        val sendBtn: LinearLayout = findViewById(R.id.chat_send)
        val openExternalBtn: LinearLayout = findViewById(R.id.open_external)

        openExternalBtn.setOnClickListener {
            val bubble = OverlayService.instance.chatHeads.activeChatHead
            if (bubble != null) {
                val scope = if (bubble.guildInfo.channel.type == ChannelType.PRIVATE) "@me" else bubble.guildInfo.id
                val url = "https://discordapp.com/channels/$scope/${bubble.guildInfo.channel.id}"
                launchDiscord(url)
            }
        }

        sendBtn.setOnClickListener {
            val bubble = OverlayService.instance.chatHeads.activeChatHead
            if (!editText.text.isNullOrEmpty()) {
                // TODO: add mentioning users
                bubble?.guildInfo?.channel?.instance?.sendMessage(editText.text)?.queue()
                editText.text.clear()
            }
        }

        scaleSpring.addListener(object : SimpleSpringListener() {
            override fun onSpringUpdate(spring: Spring) {
                scaleX = spring.currentValue.toFloat()
                scaleY = spring.currentValue.toFloat()
            }
        })
        scaleSpring.springConfig = SpringConfigs.CONTENT_SCALE

        scaleSpring.currentValue = 0.0
    }

    fun setInfo(chatHead: ChatHead) {
        if (chatHead.guildInfo.channel.type == ChannelType.PRIVATE) {
            atView.visibility = View.VISIBLE
            hashTagView.visibility = View.GONE
            channelView.text = chatHead.guildInfo.name
        } else {
            atView.visibility = View.GONE
            hashTagView.visibility = View.VISIBLE
            channelView.text = chatHead.guildInfo.channel.instance.name
        }

        lastAuthorName = null
        lastMessageGroup = null

        messagesView.removeAllViews()

        Thread {
            chatHead.guildInfo.channel.instance.history.retrievePast(50).queue { result ->
                runOnMainLoop {
                    addMessages(result)
                }
            }
        }.start()
    }

    fun launchDiscord(url: String) {
        val packageName = "com.discord"
        if (isAppInstalled(context, packageName))
            if (isAppEnabled(context, packageName)) {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.data = Uri.parse(url)
                    context.startActivity(intent)
                    OverlayService.instance.chatHeads.collapse()
                }
            }
            else
                Toast.makeText(context, "Discord app is not enabled.", Toast.LENGTH_SHORT).show()
        else
            Toast.makeText(context, "Discord app is not installed.", Toast.LENGTH_SHORT).show()
    }

    private fun isAppInstalled(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        try {
            pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            return true
        } catch (ignored: PackageManager.NameNotFoundException) {
        }

        return false
    }

    private fun isAppEnabled(context: Context, packageName: String): Boolean {
        var appStatus = false
        try {
            val ai = context.packageManager.getApplicationInfo(packageName, 0)
            if (ai != null) {
                appStatus = ai.enabled
            }
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }

        return appStatus
    }

    fun cacheAvatar(user: User): Bitmap? {
        val avatarUrl = getAvatarUrl(user)
        return if (avatarsCache[avatarUrl] == null) {
            val bmp = fetchBitmap(avatarUrl)?.makeCircular()
            avatarsCache[avatarUrl] = bmp
            bmp
        } else {
            avatarsCache[avatarUrl]
        }
    }

    fun addMessages(messages: List<Message>) {
        val lms = ArrayList<ListenableMessage>()

        val arr = messages.reversed()
        for (message in arr) {
            val lm = ListenableMessage(message)
            val view = _addMessage(message, false)

            lm.onAvatarChange = {
                view.findViewById<ImageView>(R.id.group_avatar).setImageBitmap(it)
            }

            lms.add(lm)
        }
        scrollView.isSmoothScrollingEnabled = false
        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            scrollView.isSmoothScrollingEnabled = true
        }

        Thread {
            for (lm in lms) {
                val bmp = cacheAvatar(lm.message.author)
                runOnMainLoop {
                    lm.onAvatarChange?.let { it(bmp) }
                }
            }
        }.start()
    }

    private fun _addMessage(message: Message, scrollToBottom: Boolean = true): View {
        val view: View

        if (lastAuthorName != null && lastAuthorName == message.author.name && lastMessageGroup != null) {
            view = lastMessageGroup!!
        } else {
            view = inflate(context, R.layout.message_group, null)
            val root: LinearLayout = view.findViewById(R.id.group_root)
            root.id = View.generateViewId()

            val avatarView = view.findViewById<ImageView>(R.id.group_avatar)

            avatarView.setImageBitmap(avatarsCache[getAvatarUrl(message.author)])
            view.findViewById<TextView>(R.id.group_author).text = message.author.name

            val params = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT)

            if (messagesView.childCount > 0) {
                val prev = messagesView.getChildAt(messagesView.childCount - 1)
                params.addRule(RelativeLayout.BELOW, prev.id)
                root.layoutParams = params
            } else {
                params.topMargin = dpToPx(8f)
                root.layoutParams = params
            }

            messages.addView(view)
        }

        val messagesView: LinearLayout = view.findViewById(R.id.group_messages)
        val messageView = inflate(context, R.layout.message, null)

        messageView.findViewById<TextView>(R.id.msg_body).text = message.contentRaw

        messagesView.addView(messageView)

        lastMessageGroup = view
        lastAuthorName = message.author.name

        if (scrollToBottom) {
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        return view
    }

    fun addMessage(message: Message, scrollToBottom: Boolean = true) {
        val lm = ListenableMessage(message)
        val view = _addMessage(message, scrollToBottom)

        lm.onAvatarChange = {
            view.findViewById<ImageView>(R.id.group_avatar).setImageBitmap(it)
        }

        Thread {
            val bmp = cacheAvatar(lm.message.author)
            runOnMainLoop {
                lm.onAvatarChange?.let { it(bmp) }
            }
        }.start()
    }

    fun hideContent() {
        OverlayService.instance.chatHeads.handler.removeCallbacks(
            OverlayService.instance.chatHeads.showContentRunnable)

        scaleSpring.endValue = 0.0

        val anim = AlphaAnimation(1.0f, 0.0f)
        anim.duration = 200
        anim.repeatMode = Animation.RELATIVE_TO_SELF
        startAnimation(anim)
    }

    fun showContent() {
        scaleSpring.endValue = 1.0

        val anim = AlphaAnimation(0.0f, 1.0f)
        anim.duration = 100
        anim.repeatMode = Animation.RELATIVE_TO_SELF
        startAnimation(anim)
    }
}