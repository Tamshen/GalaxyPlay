package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import com.shilapi.xcertplay.host.R

/** 竖屏模态框：标题、可滚动内容与固定操作区；保留 AlertDialog 的确认/取消契约。 */
internal class L7ModalDialog(private val owner: Context, private val content: L7Dialogs.Content) : AlertDialog(L7UiDensity.wrap(owner)) {
    private val uiContext = context
    private val returnFocus = L7DialogFocus(owner)
    private val actions = mutableMapOf<Int, Button>()
    private var choices: ListView? = null
    private var close: ImageButton? = null
    private var canCancel = content.cancelable
    fun refreshPalette(owner: Context) {
        if (owner !== this.owner || !isShowing) return
        window?.decorView?.let(L7Ui::refresh)
        val list = choices
        val checked = list?.checkedItemPosition ?: -1
        (list?.adapter as? BaseAdapter)?.notifyDataSetChanged()
        list?.divider = ColorDrawable(uiContext.getColor(R.color.product_ui_control))
        if (checked >= 0) list?.setItemChecked(checked, true)
    }
    private fun dp(value: Int) = L7Components.dp(uiContext, value)

    init {
        // 在调用者配置监听器前接入默认回调，不在 onCreate 覆盖输入清理和预听资源释放。
        setCancelable(content.cancelable)
        setCanceledOnTouchOutside(content.cancelable)
        setOnCancelListener(content.cancel)
        setOnDismissListener(content.dismiss)
    }

    override fun getButton(whichButton: Int): Button? = actions[whichButton]
    override fun getListView(): ListView? = choices

    override fun cancel() {
        // 任务弹窗先处理终止确认，不能先关闭窗口再通知业务。
        content.closeRequest?.let { it(); return }
        super.cancel()
    }

    override fun setCancelable(flag: Boolean) {
        super.setCancelable(flag)
        canCancel = flag
        close?.isEnabled = flag
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val panel = BoundedPanel(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
            L7MenuSurface.apply(this, radius = 4)
        }
        panel.addView(header())
        val body = LinearLayout(uiContext).apply { orientation = LinearLayout.VERTICAL }
        content.message?.let {
            body.addView(L7Components.text(uiContext, it.toString(), secondary = true).apply { textSize = 20f },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        }
        content.items?.let { body.addView(choiceList(it)) }
        content.view?.let { view ->
            // 内容只保留一个滚动容器，输入框和错误提示随键盘缩小窗口仍可达。
            val inner = if (view is ScrollView && view.childCount == 1) view.getChildAt(0).also { view.removeView(it) } else view
            (inner.parent as? ViewGroup)?.removeView(inner)
            styleInputs(inner)
            body.addView(inner, LinearLayout.LayoutParams(-1, -2))
        }
        panel.addView(ScrollView(uiContext).apply {
            isFillViewport = false
            addView(body)
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(20); bottomMargin = dp(20) })
        panel.addView(footer())
        setView(panel, 0, 0, 0, 0)
        super.onCreate(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        L7Dialogs.track(this)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = (uiContext.resources.displayMetrics.widthPixels * .88f).toInt().coerceAtMost(dp(720))
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(.48f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onStop() {
        L7Dialogs.untrack(this)
        super.onStop()
        returnFocus.restore()
    }

    private fun header() = LinearLayout(uiContext).apply {
        gravity = Gravity.CENTER_VERTICAL
        L7Icons.dialog(uiContext, content.title.toString())?.let { icon ->
            addView(ImageView(uiContext).apply {
                setImageResource(icon)
                L7Ui.bind(this) { imageTintList = ColorStateList.valueOf(uiContext.getColor(R.color.product_ui_text)) }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        }
        addView(L7Components.text(uiContext, content.title.toString()).apply { textSize = 26f },
            LinearLayout.LayoutParams(0, -2, 1f))
        close = ImageButton(uiContext).apply {
            setImageResource(R.drawable.ic_l7_close)
            contentDescription = uiContext.getString(R.string.close)
            L7Ui.bind(this) { imageTintList = ColorStateList.valueOf(uiContext.getColor(R.color.product_ui_muted)) }
            background = null
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isEnabled = canCancel
            setOnClickListener { if (canCancel) cancel() }
        }
        addView(close, LinearLayout.LayoutParams(dp(64), dp(64)))
    }

    private fun choiceList(items: Array<out CharSequence>): ListView {
        val height = dp((72 * uiContext.resources.configuration.fontScale.coerceAtLeast(1f)).toInt())
        return ListView(uiContext).apply {
            choices = this
            divider = ColorDrawable(uiContext.getColor(R.color.product_ui_control))
            dividerHeight = dp(1)
            choiceMode = if (content.singleChoice) ListView.CHOICE_MODE_SINGLE else ListView.CHOICE_MODE_NONE
            adapter = object : BaseAdapter() {
                override fun getCount() = items.size
                override fun getItem(position: Int) = items[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun hasStableIds() = true
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (convertView as? CheckedTextView ?: CheckedTextView(uiContext)).apply {
                        text = items[position]
                        textSize = 20f
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        minHeight = height
                        layoutParams = AbsListView.LayoutParams(-1, height)
                        L7Ui.text(this)
                        if (content.singleChoice) {
                            checkMarkDrawable = StateListDrawable().apply {
                                addState(intArrayOf(android.R.attr.state_checked), uiContext.getDrawable(R.drawable.ic_l7_radio_on))
                                addState(intArrayOf(), uiContext.getDrawable(R.drawable.ic_l7_radio_off))
                            }
                        }
                    }
            }
            if (content.singleChoice && content.checked in items.indices) setItemChecked(content.checked, true)
            setOnItemClickListener { _, _, index, _ ->
                content.itemClick?.onClick(this@L7ModalDialog, index)
                if (!content.singleChoice) dismiss()
            }
            layoutParams = LinearLayout.LayoutParams(-1, height * items.size + dp(items.size))
        }
    }

    private fun footer() = LinearLayout(uiContext).apply {
        orientation = LinearLayout.VERTICAL
        val neutral = content.actions[BUTTON_NEUTRAL]
        if (neutral != null) addView(action(BUTTON_NEUTRAL, neutral),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val row = LinearLayout(uiContext).apply {
            orientation = if (uiContext.resources.configuration.fontScale > 1.3f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        listOf(BUTTON_NEGATIVE, BUTTON_POSITIVE).forEach { which ->
            content.actions[which]?.let { value ->
                val params = if (row.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, -2, 1f)
                    else LinearLayout.LayoutParams(-1, -2)
                if (row.childCount > 0) {
                    if (row.orientation == LinearLayout.HORIZONTAL) params.marginStart = dp(12) else params.topMargin = dp(12)
                }
                row.addView(action(which, value), params)
            }
        }
        addView(row)
    }

    private fun action(which: Int, value: L7Dialogs.Action) = L7Components.actionButton(
        uiContext, value.label.toString(), primary = which == BUTTON_POSITIVE
    ) {
        actions[which]?.isEnabled = false
        value.click?.onClick(this, which)
        dismiss()
    }.also { actions[which] = it; L7Ui.button(it, primary = which == BUTTON_POSITIVE, radius = 4) }

    private fun styleInputs(view: View) {
        if (view is EditText) {
            view.textSize = 18f
            L7Ui.input(view)
        } else if (view is Button) {
            // 已由组件绑定的按钮保留主次样式，不能用正文颜色覆盖其状态与主题绑定。
            if (view.getTag(R.id.l7_palette_binding) == null) L7Ui.button(view)
        } else if (view is TextView && view.getTag(R.id.l7_palette_binding) == null) {
            view.textSize = 18f
            L7Ui.text(view, if (view.currentTextColor == uiContext.getColor(R.color.product_ui_warning))
                R.color.product_ui_warning else R.color.product_ui_muted)
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) styleInputs(view.getChildAt(index))
    }

    private class BoundedPanel(context: Context) : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val max = (resources.displayMetrics.heightPixels * .82f).toInt()
            val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) max
                else MeasureSpec.getSize(heightMeasureSpec).coerceAtMost(max)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
        }
    }
}
