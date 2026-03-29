package me.wjz.nekocrypt.util

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.abs

/**
 * 一个通用的、用于在 WindowManager 上显示 Compose UI 的弹窗工具类。
 * 它封装了所有创建、显示和销毁悬浮窗的复杂逻辑。
 *
 * @param context 上下文环境。
 * @param onDismissRequest 当弹窗被请求关闭时（例如，通过代码调用dismiss）的回调。
 * @param content 要在弹窗中显示的 Composable 内容。
 */
class NCWindowManager(
    private val context: Context,
    private val onDismissRequest: () -> Unit = {},
    private val anchorRect: Rect? = null,
    private val isDraggable: Boolean = false,
    private val focusable: Boolean = false,
    private val content: @Composable () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var popupView: View? = null
    private var lifecycleOwnerProvider: LifecycleOwnerProvider? = null
    private var positionAnimator: ValueAnimator? = null

    /**
     * 显示弹窗。
     * @param anchorRect 一个可选的矩形，用于定位弹窗。如果为null，弹窗会居中。
     */
    fun show() {
        //  防止重复显示
        if (popupView != null) return
        //  创建并启动生命周期
        lifecycleOwnerProvider = LifecycleOwnerProvider().also { it.resume() }

        //  创建ComposeView并设置内容
        popupView = ComposeView(context).apply {
            // ✨ 在设置内容之前，先给它“通上电”！
            setViewTreeLifecycleOwner(lifecycleOwnerProvider)
            setViewTreeViewModelStoreOwner(lifecycleOwnerProvider)
            setViewTreeSavedStateRegistryOwner(lifecycleOwnerProvider)
            // 不要裁剪子视图，避免子视图做动画时超出边界被裁剪
            clipChildren = false
            clipToPadding = false

            // 使用最安全通用的生命周期策略
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent(content)
        }
        if (isDraggable) {
            popupView?.setOnTouchListener(createDragTouchListener())
        }
        // 创建 WindowManager 参数
        val params = createLayoutParams(anchorRect)
        // 添加到窗口
        windowManager.addView(popupView, params)
    }

    /**
     * 关闭并销毁弹窗。
     */
    fun dismiss() {
        if (popupView != null) {
            try {
                windowManager.removeView(popupView)
            } catch (e: Exception) {
                // 忽略窗口已经不存在等异常
            } finally {
                popupView = null
                lifecycleOwnerProvider?.destroy()
                lifecycleOwnerProvider = null
                // 调用外部传入的关闭回调
                onDismissRequest()
            }
        }
    }

    private fun createLayoutParams(anchorRect: Rect?): WindowManager.LayoutParams {
        // 这个accessibility不需要用户授权，比application的好
        val layoutFlag = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        val flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            // ✨ 核心修正：加上 FLAG_LAYOUT_IN_SCREEN 这句关键的“咒语”！
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN // 这句是关键
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (focusable) {
                softInputMode =
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.blurBehindRadius = 30
        }

        if (anchorRect != null) {
            params.gravity = Gravity.TOP or Gravity.START
            params.x = anchorRect.left
            params.y = anchorRect.top
        } else {
            params.gravity = Gravity.CENTER
        }
        return params
    }

    // 带有平滑效果的位置更新函数
    fun updatePosition(targetRect: Rect) {
        // 如果视图不存在直接返回
        val view = popupView ?: return
        // 取消正在进行的任何旧动画
        positionAnimator?.cancel()

        val currentParams = view.layoutParams as? WindowManager.LayoutParams ?: return
        val startX = currentParams.x
        val startY = currentParams.y
        val endX = targetRect.left
        val endY = targetRect.top

        // 如果位置没有变化，就没必要执行动画
        if (startX == endX && startY == endY) return

        positionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250 //动画时长
            interpolator = DecelerateInterpolator() // 减速插值器，动画效果更自然
            // 根据动画进度计算当前帧的x,y坐标
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                // 根据动画进度计算当前帧的x, y坐标
                currentParams.x = (startX + (endX - startX) * fraction).toInt()
                currentParams.y = (startY + (endY - startY) * fraction).toInt()

                // 只有当视图还附着在窗口上时才更新布局
                if (view.isAttachedToWindow) {
                    windowManager.updateViewLayout(view, currentParams)
                }
            }
            start()
        }
    }

    /**
     * ✨ 全新函数：创建一个处理拖动逻辑的 OnTouchListener。
     * 它能智能地区分用户的“轻点”和“拖动”手势。
     */
    private fun createDragTouchListener(): View.OnTouchListener {
        // 手指按下时的初始位置
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var isDragging = false

        return View.OnTouchListener { view, event ->
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@OnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false // 每次按下都重置拖动状态
                    // 记录下当前窗口的位置
                    initialX = params.x
                    initialY = params.y
                    // 记录下手指在屏幕上的位置
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true // 返回 true，表示我们要继续处理后续的 MOVE 和 UP 事件
                }

                MotionEvent.ACTION_MOVE -> {
                    // 计算手指滑动的距离
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY

                    // 如果还没开始拖动，并且滑动的距离已经超过了系统阈值，那么就判定为开始拖动
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                    }

                    // 如果正在拖动，就更新窗口的位置
                    if (isDragging) {
                        params.x = (initialX + dx).toInt()
                        params.y = (initialY + dy).toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    // 如果手指抬起时，我们判定这并非一次拖动...
                    if (!isDragging) {
                        // ...那就当它是一次点击！
                        view.performClick()
                    }
                    true
                }

                else -> false // 其他事件我们不关心
            }
        }
    }
}