package me.wjz.nekocrypt.service.handler

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.toColorInt
import com.dianming.phoneapp.MyAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.wjz.nekocrypt.Constant
import me.wjz.nekocrypt.CryptoMode
import me.wjz.nekocrypt.NekoCryptApp
import me.wjz.nekocrypt.R
import me.wjz.nekocrypt.data.LocalDataStoreManager
import me.wjz.nekocrypt.ui.component.DecryptionPopup
import me.wjz.nekocrypt.ui.dialog.AttachmentPreviewState
import me.wjz.nekocrypt.ui.dialog.AttachmentState
import me.wjz.nekocrypt.ui.dialog.SendAttachmentDialog
import me.wjz.nekocrypt.util.CryptoManager
import me.wjz.nekocrypt.util.CryptoManager.applyCiphertextStyle
import me.wjz.nekocrypt.util.CryptoManager.applyCustomCiphertextStyle
import me.wjz.nekocrypt.util.CryptoManager.containsCiphertext
import me.wjz.nekocrypt.util.CryptoUploader
import me.wjz.nekocrypt.util.NCFileProtocol
import me.wjz.nekocrypt.util.NCWindowManager
import me.wjz.nekocrypt.util.ResultRelay
import me.wjz.nekocrypt.util.findSingleNode
import me.wjz.nekocrypt.util.formatFileSize
import me.wjz.nekocrypt.util.getFileName
import me.wjz.nekocrypt.util.getFileSize
import me.wjz.nekocrypt.util.getImageAspectRatio
import me.wjz.nekocrypt.util.isEmpty
import me.wjz.nekocrypt.util.isFileImage
import me.wjz.nekocrypt.util.isNodeValid
import java.io.File

// 创建一个CompositionLocal来提供给弹窗
val LocalFileActionHandler = compositionLocalOf<((NCFileProtocol) -> Unit)?> { null }

abstract class BaseChatAppHandler : ChatAppHandler {
    protected val tag = "NCBaseHandler"

    // 由子类提供具体应用的ID
    abstract override val inputId: String
    abstract override val sendBtnId: String
    abstract override val messageTextId: String
    abstract override val messageListClassName: String

    // 处理器内部状态
    private var service: MyAccessibilityService? = null

    // 按钮遮罩的管理器
    private var overlayWindowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayManagementJob: Job? = null

    // 为我们的界面节点变量做缓存
    private var cachedSendBtnNode: AccessibilityNodeInfo? = null
    private var cachedInputNode: AccessibilityNodeInfo? = null

    // 为 RecyclerView/ListView 创建一个专属的缓存
    private var cachedMessageListNode: AccessibilityNodeInfo? = null

    // 为沉浸式解密创建一个“防抖”任务，避免过于频繁的扫描
    private var immersiveDecryptionJob: Job? = null

    // Key: 一个消息气泡的唯一标识符 (位置 + 文本哈希)
    // Value: 管理这个气泡弹窗的 WindowPopupManager 实例
    private val immersiveDecryptionCache = mutableMapOf<String, NCWindowManager>()

    // ———————— 附件发送弹窗相关属性 ————————

    // 拿来判断是否拉起图片、视频弹窗。
    private var lastInputClickTime: Long = 0L
    private var filePickerJob: Job? = null // ✨ 新增一个Job来监听结果
    private var sendAttachmentDialogManager: NCWindowManager? = null

    // --- ✨ 附件发送弹窗相关的新增状态 ---
    // 使用 Compose 的 State Delegate，这样当它们的值改变时，UI会自动更新
    // ✨ 2. 只用一个 State 来管理所有UI状态
    private var attachmentState by mutableStateOf(AttachmentState())

    override fun onAccessibilityEvent(event: AccessibilityEvent, service: MyAccessibilityService) {
        // 悬浮窗管理逻辑
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (service.useAutoEncryption) {
                //只有开启加密，才会加上悬浮窗，每次事件改变，都要更新悬浮窗位置
                overlayManagementJob?.cancel()
                overlayManagementJob = service.serviceScope.launch(Dispatchers.Default) {
                    handleOverlayManagement()   // 可能是添加、更新、删除悬浮窗
                }
            } else {
                removeOverlayView()
            }
        }

        // 解密逻辑，开启解密，才进行解密操作。
        if (service.useAutoDecryption) {
            when (service.decryptionMode) {
                // 标准模式：用户点击密文时，才进行解密
                CryptoMode.STANDARD.key -> {
                    if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                        handleDecryption(event.source)
                    }
                }

                // 沉浸模式：当窗口内容变化时，主动扫描并解密
                CryptoMode.IMMERSIVE.key -> {
                    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
//                        || event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
                    ) {
                        //带防抖处理
                        immersiveDecryptionJob?.cancel()
                        // 启动一个新的扫描任务
                        immersiveDecryptionJob = service.serviceScope.launch(Dispatchers.Default) {
                            // ✨ 等待n 毫秒，如果在这期间又有新的事件进来，这个任务就会被取消
                            delay(service.decryptionWindowUpdateInterval)
                            Log.d(tag, "UI稳定，开始执行沉浸式解密...")
                            handleImmersiveDecryption()
                        }
                    }
                }
            }
        }

        // 监听点击事件，用来拉起图片视频文件发送弹窗
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            handleInputDoubleClick(event.source)
        }
    }

    // 启动服务
    override fun onHandlerActivated(service: MyAccessibilityService) {
        this.service = service
        this.overlayWindowManager =
            service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        filePickerJob = service.serviceScope.launch {
            ResultRelay.flow.collectLatest { uri ->
                // 当收到“代办”发回的URI时
                Log.d(tag, "收到文件URI: $uri")
                showAttachmentDialog()
                startUpload(uri)
            }
        }

        Log.d(tag, "激活$packageName 处理器。")
    }

    // 销毁服务
    override fun onHandlerDeactivated() {
        overlayManagementJob?.cancel()
        immersiveDecryptionJob?.cancel()
        filePickerJob?.cancel()
        // 用一个副本做遍历避免删除时下标异常
        val managersToDismiss = immersiveDecryptionCache.values.toList()
        // 依次关闭所有弹窗
        managersToDismiss.forEach { it.dismiss() }
        immersiveDecryptionCache.clear()    // 最后确保万无一失

        cachedSendBtnNode = null
        cachedInputNode = null
        cachedMessageListNode = null
        removeOverlayView {
            // 在视图置空后，其他引用量也要置为空，方便gc回收
            this.service = null
            this.overlayWindowManager = null
            Log.d(tag, "取消$packageName 处理器。")
        }
    }

    /**
     * 处理节点检查是否需要解密。
     * @param sourceNode 用户点击的源节点。
     */
    private fun handleDecryption(sourceNode: AccessibilityNodeInfo?) {
        val node = sourceNode ?: return
        val text = node.text?.toString() ?: return
        tryDecryptingText(text)?.let {
            Log.d(tag, "解密成功 -> $it")
            showDecryptionPopup(
                decryptedText = it,
                anchorNode = node,
            )
        }
    }

    /**
     * 执行沉浸式解密相关逻辑。
     */
    private fun handleImmersiveDecryption() {
        runCatching {
            val currentService = service ?: return
            val root = if (currentService.rootInActiveWindow.isEmpty()) getActiveWindowRoot()
            else currentService.rootInActiveWindow
            // 更新缓存节点
            if (!isNodeValid(cachedMessageListNode)) {
                if (root == null) {
                    Log.e(tag, "root节点为空，handlerImmersiveDecryption失败")
                    return
                }
                // 拿recycleView
                cachedMessageListNode = findSingleNode(
                    rootNode = root,
                    className = messageListClassName
                )
            }

            val messageNodes = if (cachedMessageListNode == null) findAllTextNodes(root!!) else
                cachedMessageListNode!!.findAccessibilityNodeInfosByViewId(messageTextId)

            if (messageNodes.isNullOrEmpty()) {
                Log.d(tag, "消息列表中无消息或已离开聊天界面，开始清理所有弹窗...")
                // 如果找不到任何消息内容，清空缓存
                if (immersiveDecryptionCache.isNotEmpty()) {
                    currentService.serviceScope.launch(Dispatchers.Main) {
                        val managersToDismiss = immersiveDecryptionCache.values.toList()
                        Log.d(tag, "清理 ${managersToDismiss.size} 个残留弹窗。")
                        managersToDismiss.forEach { it.dismiss() }
                    }
                }
                return
            }

            // 分成三类
            val visibleCacheKeys = mutableSetOf<String>()   // 此轮可见的缓存key。
            val creationTasks = mutableListOf<Triple<String, AccessibilityNodeInfo, String>>()
            val updateTasks = mutableListOf<Pair<NCWindowManager, Rect>>()

            for (node in messageNodes) {
                // 解密出内容，再做处理，否则直接跳过
                tryDecryptingText(node.text?.toString())?.let { decryptedText ->
                    val nodeBounds = Rect()
                    node.getBoundsInScreen(nodeBounds)
                    val cacheKey = decryptedText.hashCode().toString()   // key就直接哈希
                    visibleCacheKeys.add(cacheKey)

                    // 如果弹窗已经存在，就加入更新位置的任务队列里
                    immersiveDecryptionCache[cacheKey]?.let { manager ->
                        updateTasks.add(manager to nodeBounds)
                    } ?: run {
                        // 如果弹窗不存在，则加入“创建弹窗”任务列表
                        creationTasks.add(Triple(decryptedText, node, cacheKey))
                    }
                }
            }
            // 找到需要被清除的弹窗。比如用户滑动了窗口，有的弹窗对应的气泡不再可见，就需要消失。
            val cachedKeys = immersiveDecryptionCache.keys.toSet()
            val keysToDismiss = cachedKeys - visibleCacheKeys

            if (keysToDismiss.isNotEmpty() || updateTasks.isNotEmpty() || creationTasks.isNotEmpty()) {
                Log.d(tag, "--- 沉浸式解密任务分配 ---")
                Log.d(tag, "需要销毁的弹窗 (${keysToDismiss.size}个): $keysToDismiss")
                Log.d(tag, "需要更新位置的弹窗 (${updateTasks.size}个)")
                Log.d(
                    tag,
                    "需要新创建的弹窗 (${creationTasks.size}个): ${creationTasks.map { it.third }}"
                )
                Log.d(tag, "--------------------------")
            }

            // 整理完毕，在主线程执行操作
            if (keysToDismiss.isNotEmpty() || updateTasks.isNotEmpty() || creationTasks.isNotEmpty()) {
                currentService.serviceScope.launch(Dispatchers.Main) {
                    keysToDismiss.forEach {
                        immersiveDecryptionCache[it]?.dismiss() // dismiss里面会自动让对象本身为null
                    }
                    updateTasks.forEach { (manager, rect) ->
                        if (!isActive) return@forEach
                        manager.updatePosition(rect)
                    }
                    creationTasks.forEach { (decryptedText, node, cacheKey) ->
                        if (!isActive) return@forEach

                        // ✨ [正确逻辑] 1. 调用通用函数，并传入“从缓存移除自己”的正确回调
                        val popupManager = showDecryptionPopup(
                            decryptedText = decryptedText,
                            anchorNode = node,
                            showTime = 30000, // 配置项为 currentService.decryptionWindowShowTime
                            onDismiss = {
                                // 这个回调在弹窗关闭时执行，完美地维护了缓存
                                immersiveDecryptionCache.remove(cacheKey)
                                Log.d(tag, "弹窗关闭，从缓存中移除: $cacheKey")
                            }
                        )
                        // ✨ [正确逻辑] 2. 将返回的管理器实例存入缓存
                        immersiveDecryptionCache[cacheKey] = popupManager
                        Log.d(tag, "新弹窗已创建并加入缓存: $cacheKey")
                    }
                }
            }
        }.onFailure { exception ->
            Log.e(
                tag,
                "handlerImmersiveDecryption error:${exception.message}"
            )
        }
    }

    /**
     * 它会判断解密后的内容是普通文本还是我们的文件协议，并显示不同的UI。
     */
    private fun showDecryptionPopup(
        decryptedText: String,
        anchorNode: AccessibilityNodeInfo,
        showTime: Long = service!!.decryptionWindowShowTime,
        onDismiss: (() -> Unit)? = null,
    ): NCWindowManager {

        val anchorRect = Rect()
        anchorNode.getBoundsInScreen(anchorRect)

        var popupManager: NCWindowManager? = null
        popupManager = NCWindowManager(
            context = service!!,
            onDismissRequest = {
                onDismiss?.invoke()
                popupManager = null
            },
            anchorRect = anchorRect
        ) {
            // ✨ 使用 CompositionLocalProvider 将 fileActionHandler 的 show 方法放入“魔法通道”
            CompositionLocalProvider(
                LocalFileActionHandler provides { fileInfo -> FileActionHandler(service!!).show(fileInfo) }
            ) {
                DecryptionPopup(
                    decryptedText = decryptedText,
                    onDismiss = { popupManager?.dismiss() },
                    durationMills = showTime
                )
            }
        }
        popupManager!!.show()
        return popupManager!!
    }

    // --- 所有悬浮窗和加密逻辑都内聚在这里 ---

    /**
     * ✨ 终极形态的悬浮窗管理逻辑 ✨
     * 先检查缓存，再搜索
     */
    protected fun handleOverlayManagement() {
        runCatching {
            var sendBtnNode: AccessibilityNodeInfo?

            // 1. 优先信任缓存
            if (isNodeValid(cachedSendBtnNode)) {
                sendBtnNode = cachedSendBtnNode
            }
            // 2. 缓存无效，则进行查找
            else {
                val currentService = service ?: return

                val rootNode =
                    if (currentService.rootInActiveWindow.isEmpty()) getActiveWindowRoot()
                    else currentService.rootInActiveWindow ?: run {
                        Log.e(tag, "根节点为空，handleOverlayManagement失败")
                        return
                    }

                Log.d(tag, "尝试在根节点 ${rootNode?.className} 中查找发送按钮...")

                sendBtnNode = findSingleNode(rootNode!!, sendBtnId)
                //sendBtnNode = findNodeById(rootNode!!,sendBtnId)
                cachedSendBtnNode = sendBtnNode
            }

            // 3. 根据最终的节点状态来决定如何操作
            if (sendBtnNode != null) {
                val rect = Rect()
                sendBtnNode.getBoundsInScreen(rect)
                if (!rect.isEmpty) {
                    createOrUpdateOverlayView(rect)
                } else {
                    Log.d(tag, "按钮虽存在但没有实际尺寸！")
                    removeOverlayView()
                }
            } else {
                Log.d(tag, "未找到有效发送按钮节点！")
                removeOverlayView()
            }
        }.onFailure { exception -> Log.e(tag, "handleOverlayManagement错误。${exception.message}") }
    }

    /**
     * @param rect 悬浮窗的目标位置和大小。
     */
    protected fun createOrUpdateOverlayView(rect: Rect) {
        val currentService = service ?: return
        //  绘制悬浮窗位置所需要用到的参数
        val params = getOverlayLayoutParams(rect)
        currentService.serviceScope.launch(Dispatchers.Main) {
            if (overlayView == null) {
                overlayView = View(currentService).apply {
                    setBackgroundColor(currentService.sendBtnOverlayColor.toColorInt())

                    // ✨ 1. 首先，为视图定义一个标准的“单击”行为
                    // 这就是我们的“标准门铃按钮”。
                    setOnClickListener {
                        // 标准模式下，短按执行普通发送
                        if (currentService.encryptionMode == CryptoMode.STANDARD.key) {
                            Log.d(currentService.tag, "标准模式短按，执行普通发送！")
                            doNormalClick()
                        }
                        // 沉浸模式下，短按（单击）也执行加密发送
                        else if (currentService.encryptionMode == CryptoMode.IMMERSIVE.key) {
                            Log.d(currentService.tag, "沉浸模式点击，执行加密！")
                            doEncryptAndClick()
                        }
                    }

                    // ✨ 2. 然后，我们只用 onTouch 来“监听”手势，特别是长按
                    var longPressJob: Job? = null
                    setOnTouchListener { v, event -> // 'v' 就是这个 View 本身
                        // 只在标准模式下才需要区分长按和短按
                        if (currentService.encryptionMode == CryptoMode.STANDARD.key) {
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    longPressJob = currentService.serviceScope.launch {
                                        delay(currentService.longPressDelay) // 长按阈值
                                        Log.d(currentService.tag, "标准模式长按，执行加密！")
                                        doEncryptAndClick()
                                    }
                                    true // 我们要处理后续事件
                                }

                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    // 如果手指抬起时，长按任务还在“准备中”...
                                    if (longPressJob?.isActive == true) {
                                        // ...说明这是一个短按，取消长按任务...
                                        longPressJob.cancel()
                                        // ✨ ...然后“按响”那个标准的门铃！
                                        v.performClick()
                                    }
                                    // 如果长按任务已经执行或被取消，这里就什么都不做
                                    true
                                }

                                else -> false
                            }
                        } else {
                            // 在沉浸模式下，我们让标准的 OnClickListener 去处理所有点击
                            // onTouch 只需要返回 false，表示“我不管，让别人来处理”
                            false
                        }
                    }
                }
                overlayWindowManager?.addView(overlayView, params)
                // 第一次添加进去的时候，位置很可能是歪的，延迟一定时间然后更新悬浮窗位置。
                delay(1500)
                val rect = Rect()
                cachedSendBtnNode?.getBoundsInScreen(rect)
                // overlayView存在才可以这么做
                overlayView?.let {
                    overlayWindowManager?.updateViewLayout(overlayView, getOverlayLayoutParams(rect))
                }
                Log.d(tag, "悬浮窗位置修正，修正后位置：$rect")
            } else {
                overlayWindowManager?.updateViewLayout(overlayView, params)
            }
        }
    }

    // 移除悬浮窗
    protected fun removeOverlayView(onComplete: (() -> Unit)? = null) {
        service?.serviceScope?.launch(Dispatchers.Main) {
            if (overlayView != null && overlayWindowManager != null) {
                overlayWindowManager?.removeView(overlayView)
                overlayView = null

                onComplete?.invoke()
            }
        }
    }

    // 自动加密并发送消息
    protected fun doEncryptAndClick(hiddenText: String? = null, key: String? = null) {
        runCatching {
            val currentService = service ?: return
            // ✨ 使用一个单独的协程来准备加密文本，避免阻塞
            // 1. 查找输入框以获取原始文本
            val root = if (service!!.rootInActiveWindow.isEmpty()) getActiveWindowRoot()
            else currentService.rootInActiveWindow

            val inputNode = findSingleNode(root!!, inputId, Constant.EDIT_TEXT)
            val originalText = inputNode?.text?.toString()

            if (originalText.isNullOrBlank()) {
                currentService.serviceScope.launch {
                    showToast("聊天框为空，无法发送")
                }
                return
            }

            // 2. 加密文本
            val encryptedText = if (hiddenText.isNullOrBlank()) {
                if (originalText.containsCiphertext()) {
                    originalText
                } else {
                    CryptoManager.encrypt(originalText, key ?: currentService.currentKey)
                        .applyCiphertextStyle()
                }
            } else {
                // 逻辑反过来：
                // 当前聊天框里的内容 = 表面显示文字
                // 弹窗输入框里的内容 = 被转换为透明字符的隐藏文字
                CryptoManager.encrypt(hiddenText, key ?: currentService.currentKey)
                    .applyCustomCiphertextStyle(originalText)
            }

            // 3. 调用核心发送函数
            setTextAndSend(encryptedText)
        }.onFailure { exception -> Log.e(tag, "doEncryptAndClick Error${exception.message}") }
    }

    private fun getCurrentInputText(): String? {
        val currentService = service ?: return null
        val root = if (currentService.rootInActiveWindow.isEmpty()) getActiveWindowRoot()
        else currentService.rootInActiveWindow
        val inputNode = root?.let { findSingleNode(it, inputId, Constant.EDIT_TEXT) }
        return inputNode?.text?.toString()
    }

    // 普通点击的发送逻辑 (用于标准模式的短按)
    protected fun doNormalClick() {
        if (!isNodeValid(cachedSendBtnNode)) {
            val root = service?.rootInActiveWindow ?: return
            cachedSendBtnNode = findSingleNode(root, sendBtnId)
        }
        cachedSendBtnNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findAllTextNodes(rootNode: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()

        fun searchRecursively(node: AccessibilityNodeInfo) {
            // 1. 检查当前节点本身是否有可见的、非空白的文本
            if (!node.text.isNullOrBlank()) {
                // 为了避免把整个列表容器（它也可能有text）加进去，我们可以加一个额外的判断
                // 比如，它的子节点不能再有包含文本的TextView了。
                // 但为了简单和通用，我们先直接添加。
                results.add(node)
            }

            // 2. 遍历所有子节点，并对每个子节点递归调用自己
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    searchRecursively(child)
                    // 回收节点，防止内存泄漏
                    child.recycle()
                }
            }
        }

        searchRecursively(rootNode)
        return results
    }

    /**
     * 使用 ACTION_SET_TEXT 来直接设置文本，这是更安全、更专业的做法。
     * 它不会污染用户的剪贴板！
     * @param nodeInfo 目标节点。
     * @param text 要设置的文本。
     */
    protected fun performSetText(nodeInfo: AccessibilityNodeInfo, text: String): Boolean {
        // 检查节点是否支持“设置文本”这个动作。
        if (nodeInfo.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)) {
            Log.d(tag, "准备设置加密文本到输入框")
            // 1. 创建一个 Bundle (包裹)，用来存放我们要设置的文本。
            val arguments = Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )

            // 2. 对节点下达“执行设置文本”的命令，并把装有文本的“包裹”递给它。
            if (!nodeInfo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                Log.e(tag, "performAction(ACTION_SET_TEXT) 直接返回失败。")
                return false
            }
            // 刷新节点，验证文本内容是否正确更新
            nodeInfo.refresh()
            if (nodeInfo.text.toString() == text) {
                Log.d(tag, "已将加密内容直接设置到节点: $text")
                return true
            } else {
                Log.d(tag, "加密内容设置到textField失败，可能是内容过长: ${text.length}字")
                return false
            }
        } else {
            // 如果节点不支持直接设置文本（比如不可编辑的TextView），我们再考虑其他策略。
            // 比如弹窗提示，或者把解密内容复制到剪贴板（并明确告知用户）。
            Log.d(tag, "节点不支持设置文本。加密内容: $text")
            return false
        }
    }

    /**
     * ✨ 获取当前活跃窗口的根节点
     * 优先寻找那个既活跃又获得焦点的窗口，这通常是用户正在交互的主窗口。
     * @return 活跃窗口的根节点，如果找不到则返回null。
     */
    private fun getActiveWindowRoot(): AccessibilityNodeInfo? {
        runCatching {
            //从service里面拿的rootInActiveWindow不一定是真的，微信在部分端上拿到的root属性全为null，得想办法解决。
            val windows = service!!.windows
            // 优先寻找既活跃又获得焦点的窗口
            val activeFocusedWindow = windows.find { it.isActive && it.isFocused }
            if (activeFocusedWindow?.root != null) {
                //Log.d(tag, "✅ 成功定位到活跃且获得焦点的窗口 (ID: ${activeFocusedWindow.id})")
                return activeFocusedWindow.root
            }

            // 如果找不到，退而求其次，寻找第一个活跃的窗口
            val activeWindow = windows.find { it.isActive }
            if (activeWindow?.root != null) {
                Log.w(tag, "⚠️ 未找到获得焦点的窗口，回退到第一个活跃窗口 (ID: ${activeWindow.id})")
                return activeWindow.root
            }

            // 如果连活跃窗口都没有，就默认返回第一个
            return service!!.rootInActiveWindow
        }.onFailure { exception -> Log.e(tag, "getActiveWindowRoot Error:${exception.message}") }
        return null
    }

    /**
     * 处理输入框双击事件逻辑
     */
    private fun handleInputDoubleClick(sourceNode: AccessibilityNodeInfo?) {
        val node = sourceNode ?: return
        val currentService = service ?: return
        // 1. 检查被点击的节点是不是我们关心的那个输入框
        //    我们通过比较节点的 viewIdResourceName 来确认它的身份
        if (node.viewIdResourceName == inputId) {
            val currentTime = System.currentTimeMillis()

            // 2. 检查距离上次点击的时间，是否在我们的“双击”阈值之内
            if (currentTime - lastInputClickTime < currentService.showAttachmentViewDoubleClickThreshold) {
                Log.d(tag, "检测到输入框双击事件, 准备启动发送附件Activity")

                // 双击当下直接检查聊天框里有没有内容
                val currentInputText = node.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: getCurrentInputText()

                showAttachmentDialog(
                    hasChatInputText = !currentInputText.isNullOrBlank()
                )
                lastInputClickTime = 0L
            } else {
                // 如果是第一次点击，或者距离上次点击太久，就只更新时间戳
                lastInputClickTime = currentTime
            }
        }
    }

    fun getOverlayLayoutParams(anchorRect: Rect): WindowManager.LayoutParams {
        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        return WindowManager.LayoutParams(
            anchorRect.width(),
            anchorRect.height(),
            anchorRect.left,
            anchorRect.top,
            layoutFlag,
            FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_IN_SCREEN,// 这句FLAG_LAYOUT_IN_SCREEN是关键
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    /**
     * 创建并显示“发送附件”对话框
     */
    private fun showAttachmentDialog(hasChatInputText: Boolean = !getCurrentInputText().isNullOrBlank()) {
        // 每次创建的时候就重置attachmentState
        resetAttachmentState()
        val currentService = service ?: return
        if (sendAttachmentDialogManager != null) return

        sendAttachmentDialogManager = NCWindowManager(
            context = currentService,
            onDismissRequest = { sendAttachmentDialogManager = null },
            anchorRect = null,
            focusable = true
        ) {
            CompositionLocalProvider(
                LocalDataStoreManager provides NekoCryptApp.instance.dataStoreManager
            ) {
                SendAttachmentDialog(
                    onDismissRequest = { sendAttachmentDialogManager?.dismiss() },
                    onSendRequest = { url ->
                        // 发送成功后，也关闭对话框
                        Log.d(tag, "准备发送URL: $url")
                        setTextAndSend(url)
                        sendAttachmentDialogManager?.dismiss()
                    },
                    onCustomTextSendRequest = { hiddenText, selectedKey ->
                        // 先关闭弹窗，再等待界面恢复后发送
                        sendAttachmentDialogManager?.dismiss()
                        currentService.serviceScope.launch {
                            delay(120)
                            doEncryptAndClick(hiddenText, selectedKey)
                        }
                    },
                    hasChatInputText = hasChatInputText,
                    attachmentState = attachmentState
                )
            }
        }
        sendAttachmentDialogManager?.show()
    }

    /**
     * ✨ 核心的、可复用的“设置文本并发送”函数
     * 它封装了查找节点、设置文本、轮询查找按钮并点击的完整健壮流程。
     * @param textToSet 需要设置到输入框的最终文本。
     */
    private fun setTextAndSend(textToSet: String) {
        val currentService = service
        if (currentService == null) {
            Log.d(tag, "service为null！不执行发送！")
            return
        }

        currentService.serviceScope.launch {
            var setSuccess = false

            // --- 1. 反复获取当前 root 和输入框，直到成功设置文本 ---
            for (attempt in 0 until 8) {
                val currentRoot = if (currentService.rootInActiveWindow.isEmpty()) {
                    getActiveWindowRoot()
                } else {
                    currentService.rootInActiveWindow
                }

                if (currentRoot == null) {
                    Log.d(tag, "第 ${attempt + 1} 次尝试获取 root 失败...")
                    delay(120)
                    continue
                }

                cachedInputNode = findSingleNode(currentRoot, inputId, Constant.EDIT_TEXT)
                    ?: cachedInputNode?.takeIf { isNodeValid(it) }

                if (cachedInputNode == null) {
                    Log.d(tag, "第 ${attempt + 1} 次尝试查找输入框失败...")
                    delay(120)
                    continue
                }

                if (performSetText(cachedInputNode!!, textToSet)) {
                    setSuccess = true
                    break
                } else {
                    Log.d(tag, "第 ${attempt + 1} 次尝试设置文本失败...")
                    delay(120)
                }
            }

            if (!setSuccess) {
                Log.e(tag, "发送失败：未能成功设置输入框文本！")
                showToast(currentService.getString(R.string.set_text_failed, textToSet.length))
                return@launch
            }

            // 给界面一点时间，让发送按钮重新出现 / 刷新
            delay(120)

            // --- 2. 每次都重新获取当前 root，再查找发送按钮 ---
            var sendSuccess = false
            for (attempt in 0 until 10) {
                val currentRoot = if (currentService.rootInActiveWindow.isEmpty()) {
                    getActiveWindowRoot()
                } else {
                    currentService.rootInActiveWindow
                }

                if (currentRoot == null) {
                    Log.d(tag, "第 ${attempt + 1} 次尝试获取发送阶段 root 失败...")
                    delay(120)
                    continue
                }

                cachedSendBtnNode = findSingleNode(currentRoot, sendBtnId, Constant.VIEW_ID_BTN)
                    ?: cachedSendBtnNode?.takeIf { isNodeValid(it) }

                if (cachedSendBtnNode != null) {
                    Log.d(tag, "成功找到发送按钮，执行点击！")
                    sendSuccess = cachedSendBtnNode!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (sendSuccess) break
                }

                Log.d(tag, "第 ${attempt + 1} 次尝试查找/点击发送按钮失败...")
                delay(120)
            }

            if (!sendSuccess) {
                Log.e(tag, "发送失败：在设置文本后，依然未能找到发送按钮！")
                showToast("发送失败：找不到发送按钮")
            }
        }
    }

    // 收到flow中的uri之后，读取资源并上传。附带了更新预览状态
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startUpload(uri: Uri) {
        val currentService = service ?: return
        // 在IO线程读取文件
        currentService.serviceScope.launch(Dispatchers.IO) {
            try {
                val fileSize = getFileSize(uri)
                // 判断文件大小。当前接口传图片最大支持10M。
                if (fileSize > 1024 * 1024 * 10) {
                    showToast(
                        currentService.getString(
                            R.string.crypto_attachment_file_too_large,
                            10
                        )
                    )
                    return@launch
                }

                showToast(
                    currentService.getString(
                        R.string.crypto_attachment_chosen_path,
                        uri.path
                    )
                )

                // 更新预览状态
                updateAttachmentState { currentState ->
                    currentState.copy(
                        previewInfo = AttachmentPreviewState(
                            uri = uri,
                            fileName = getFileName(uri),
                            fileSizeFormatted = fileSize.formatFileSize(),
                            isImage = isFileImage(uri),
                            imageAspectRatio = getImageAspectRatio(uri)
                        )
                    )
                }

                // 开始上传，先拿到bytes，拿不到就直接返回。
                val fileBytes = currentService.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@launch

                // 目前上传接口似乎不支持流式上传。
                val result: NCFileProtocol = CryptoUploader.upload(
                    fileBytes = fileBytes,
                    encryptionKey = currentService.currentKey,
                    fileName = getFileName(uri),
                    onProcess = { progressInt ->
                        // 将 0-100 的 Int 进度转换为 0.0-1.0 的 Float
                        val progressFloat = progressInt / 100.0f
                        // 在主线程更新UI
                        launch(Dispatchers.Main) {
                            updateAttachmentState { currentState ->
                                currentState.copy(progress = progressFloat)
                            }
                        }
                    },
                )

//                val result : NCFileProtocol = CryptoUploader.upload(
//                    uri = uri,
//                    encryptionKey = currentService.currentKey,
//                    fileName = getFileName(uri),
//                    onProcess = { progressInt ->
//                        // 将 0-100 的 Int 进度转换为 0.0-1.0 的 Float
//                        val progressFloat = progressInt / 100.0f
//                        // 在主线程更新UI
//                        launch(Dispatchers.Main) {
//                            updateAttachmentState { currentState ->
//                                currentState.copy(progress = progressFloat)
//                            }
//                        }
//                    },
//                )

                // 4. 上传成功，更新UI
                updateAttachmentState { currentState ->
                    currentState.copy(
                        result = result.toEncryptedString(currentService.currentKey),
                        progress = null
                    )
                }
                Log.d(tag, "上传成功，结果: $result")

            } catch (e: Exception) {
                // 5. 统一处理所有异常
                Log.e(tag, "上传失败: ", e)
                showToast(
                    currentService.getString(
                        R.string.crypto_attachment_upload_failed,
                        e.message
                    )
                )
                resetAttachmentState()
            } finally {
                // 无论文件上传成功与否，如果scheme是file，说明是我们创建的临时文件，删掉
                if (uri.scheme == "file") {
                    uri.path?.let { path ->
                        val cacheFile = File(path)
                        if (cacheFile.exists()) {
                            val deleted = cacheFile.delete()
                            if (deleted) {
                                Log.d(tag, "✅ 临时缓存文件已成功删除: $path")
                            } else {
                                Log.w(tag, "⚠️ 临时缓存文件删除失败: $path")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * ✨ 核心的“解密引擎”函数
     * 它的职责单一，就是尝试解密一段文本。
     * @param textToDecrypt 可能包含密文的原始字符串。
     * @return 如果解密成功，返回明文字符串；否则返回null。
     */
    private fun tryDecryptingText(textToDecrypt: String?): String? {
        if (textToDecrypt == null) return null
        val currentService = service ?: return null
        // 1. 先判断是否真的包含“猫语”，避免不必要的计算
        if (!textToDecrypt.containsCiphertext()) {
            return null
        }
        Log.d(tag, "检测到密文: $textToDecrypt")
        // 2. 尝试用所有密钥进行解密
        Log.d(tag, "目前的全部密钥${currentService.cryptoKeys.joinToString()}")
        // 2. 遍历所有密钥进行尝试
        for (key in currentService.cryptoKeys) {
            val decryptedText = CryptoManager.decrypt(textToDecrypt, key)
            if (decryptedText != null) {
                // 3. 只要有一个成功，就立刻返回结果
                Log.d(tag, "解密成功 -> $decryptedText")
                return decryptedText
            }
        }
        // 4. 如果所有密钥都失败了，返回null
        return null
    }

    // 重置附件的状态
    fun resetAttachmentState() {
        attachmentState = AttachmentState()
    }

    // 更新附件状态
    private suspend fun updateAttachmentState(updater: (currentState: AttachmentState) -> AttachmentState) {
        // 使用 serviceScope 在主线程安全地更新状态
        withContext(Dispatchers.Main) {
            attachmentState = updater(attachmentState)
        }
    }

    suspend fun showToast(string: String, duration: Int = Toast.LENGTH_SHORT) {
        Log.d(tag, "showToast: $string")
        withContext(Dispatchers.Main) {
            Toast.makeText(service, string, duration).show()
        }
    }
}