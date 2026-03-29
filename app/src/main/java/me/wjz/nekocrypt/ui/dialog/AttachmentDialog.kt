package me.wjz.nekocrypt.ui.dialog

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.wjz.nekocrypt.Constant
import me.wjz.nekocrypt.R
import me.wjz.nekocrypt.SettingKeys
import me.wjz.nekocrypt.data.rememberKeyArrayState
import me.wjz.nekocrypt.hook.rememberDataStoreState
import me.wjz.nekocrypt.ui.activity.AttachmentPickerActivity
import me.wjz.nekocrypt.ui.theme.NekoCryptTheme
import me.wjz.nekocrypt.util.CiphertextStyleType

data class AttachmentState(
    var progress: Float? = null,
    var previewInfo: AttachmentPreviewState? = null,
    var result: String = "",
) {
    val isUploading: Boolean
        get() = progress != null

    val isUploadFinished: Boolean
        get() = result.isNotEmpty()
}

data class AttachmentPreviewState(
    var uri: Uri,
    var fileName: String,
    var fileSizeFormatted: String,
    var isImage: Boolean,
    val imageAspectRatio: Float? = null,
)

private enum class MenuContent {
    NONE,
    STYLE,
    KEY,
    PREVIEW
}

@Composable
fun SendAttachmentDialog(
    attachmentState: AttachmentState,
    hasChatInputText: Boolean,
    onDismissRequest: () -> Unit,
    onSendRequest: (String) -> Unit,
    onCustomTextSendRequest: (String, String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var currentContent by remember { mutableStateOf(MenuContent.NONE) }
    var customDisplayText by remember { mutableStateOf("") }

    val keysFromDataStore: Array<String> by rememberKeyArrayState()

    var activeKey by rememberDataStoreState(
        SettingKeys.CURRENT_KEY,
        Constant.DEFAULT_SECRET_KEY
    )

    var ciphertextStyleType by rememberDataStoreState(
        SettingKeys.CIPHERTEXT_STYLE,
        CiphertextStyleType.NEKO.toString()
    )

    val displayKeys = remember(keysFromDataStore, activeKey) {
        when {
            keysFromDataStore.isNotEmpty() -> keysFromDataStore.toList()
            activeKey.isNotBlank() -> listOf(activeKey)
            else -> listOf(Constant.DEFAULT_SECRET_KEY)
        }
    }

    LaunchedEffect(attachmentState.previewInfo, attachmentState.result) {
        if (attachmentState.previewInfo != null && attachmentState.result.isNotEmpty()) {
            currentContent = MenuContent.PREVIEW
        } else if (currentContent == MenuContent.PREVIEW) {
            currentContent = MenuContent.NONE
        }
    }

    fun dismissDirectly() {
        coroutineScope.launch {
            delay(80)
            onDismissRequest()
        }
    }

    // 现在这里输入的是“隐藏文字”，只有聊天框已有表面显示文字时才有意义
    val canUseCustomText = hasChatInputText && !attachmentState.isUploading
    val canSendCustomText = customDisplayText.isNotBlank() && hasChatInputText && !attachmentState.isUploading
    val canSendAttachment = attachmentState.isUploadFinished && !attachmentState.isUploading

    // 弹出后自动聚焦到输入框并尝试拉起输入法
    LaunchedEffect(canUseCustomText) {
        if (canUseCustomText) {
            delay(150)
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    NekoCryptTheme(darkTheme = false) {
        Box(
            modifier = Modifier
                .padding(8.dp)
                .imePadding()
                .navigationBarsPadding()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .heightIn(max = 560.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "菜单",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Box(contentAlignment = Alignment.Center) {
                        AttachmentOptions(
                            isUploading = attachmentState.isUploading,
                            onDismissAfterPick = { dismissDirectly() },
                            onStyleClick = {
                                currentContent =
                                    if (currentContent == MenuContent.STYLE) MenuContent.NONE
                                    else MenuContent.STYLE
                            },
                            onKeyClick = {
                                currentContent =
                                    if (currentContent == MenuContent.KEY) MenuContent.NONE
                                    else MenuContent.KEY
                            }
                        )

                        if (attachmentState.isUploading) {
                            Row(horizontalArrangement = Arrangement.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        progress = { attachmentState.progress ?: 0f }
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.crypto_attachment_uploading),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    if (currentContent != MenuContent.NONE) {
                        Spacer(modifier = Modifier.height(16.dp))

                        when (currentContent) {
                            MenuContent.STYLE -> {
                                InlineSingleChoicePanel(
                                    title = "语种",
                                    items = CiphertextStyleType.entries.map {
                                        it.toString() to stringResource(it.displayNameResId)
                                    },
                                    selectedKey = ciphertextStyleType,
                                    onItemClick = { selected ->
                                        ciphertextStyleType = selected
                                    }
                                )
                            }

                            MenuContent.KEY -> {
                                InlineSingleChoicePanel(
                                    title = "密钥",
                                    items = displayKeys.map { key -> key to key },
                                    selectedKey = activeKey,
                                    onItemClick = { selected ->
                                        activeKey = selected
                                    }
                                )
                            }

                            MenuContent.PREVIEW -> {
                                val currentPreview by rememberUpdatedState(attachmentState.previewInfo)
                                currentPreview?.let {
                                    FilePreview(
                                        uri = it.uri,
                                        fileName = it.fileName,
                                        fileSize = it.fileSizeFormatted,
                                        isImage = it.isImage,
                                        aspectRatio = it.imageAspectRatio
                                    )
                                }
                            }

                            MenuContent.NONE -> Unit
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = customDisplayText,
                        onValueChange = { customDisplayText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        enabled = canUseCustomText,
                        singleLine = true,
                        placeholder = {
                            Text("隐藏文字!仅聊天框非空时生效")
                        }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = onDismissRequest,
                            enabled = !attachmentState.isUploading
                        ) {
                            Text(stringResource(R.string.cancel))
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                if (canSendCustomText) {
                                    onCustomTextSendRequest(customDisplayText, activeKey)
                                } else {
                                    onSendRequest(attachmentState.result)
                                }
                            },
                            enabled = canSendCustomText || canSendAttachment
                        ) {
                            Text(stringResource(R.string.send))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FilePreview(
    uri: Uri,
    fileName: String,
    fileSize: String,
    isImage: Boolean,
    aspectRatio: Float?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        if (isImage) {
            AsyncImage(
                model = uri,
                contentDescription = "Image Preview",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .let {
                        if (aspectRatio != null && aspectRatio > 0f) {
                            it.aspectRatio(aspectRatio)
                        } else {
                            it.height(180.dp)
                        }
                    }
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.AttachFile,
                    contentDescription = "File Icon",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = fileSize,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentOptions(
    isUploading: Boolean,
    modifier: Modifier = Modifier,
    onDismissAfterPick: () -> Unit,
    onStyleClick: () -> Unit,
    onKeyClick: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SendOptionItem(
                icon = Icons.Outlined.Collections,
                label = "相册",
                enabled = !isUploading,
                onClick = {
                    val intent = Intent(context, AttachmentPickerActivity::class.java).apply {
                        putExtra(
                            AttachmentPickerActivity.EXTRA_PICK_TYPE,
                            AttachmentPickerActivity.TYPE_MEDIA
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    onDismissAfterPick()
                }
            )

            SendOptionItem(
                icon = Icons.Outlined.FileOpen,
                label = "文件",
                enabled = !isUploading,
                onClick = {
                    val intent = Intent(context, AttachmentPickerActivity::class.java).apply {
                        putExtra(
                            AttachmentPickerActivity.EXTRA_PICK_TYPE,
                            AttachmentPickerActivity.TYPE_FILE
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    onDismissAfterPick()
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SendOptionItem(
                icon = Icons.Outlined.Translate,
                label = "语种",
                enabled = !isUploading,
                onClick = onStyleClick
            )

            SendOptionItem(
                icon = Icons.Filled.VpnKey,
                label = "密钥",
                enabled = !isUploading,
                onClick = onKeyClick
            )
        }
    }
}

@Composable
private fun InlineSingleChoicePanel(
    title: String,
    items: List<Pair<String, String>>,
    selectedKey: String,
    onItemClick: (String) -> Unit,
) {
    val scrollState = rememberScrollState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items.forEach { (key, label) ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onItemClick(key) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (key == selectedKey) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = key == selectedKey,
                                onClick = { onItemClick(key) },
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.SendOptionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(12.dp)

    Surface(
        modifier = modifier
            .weight(1f)
            .clip(shape)
            .clickable(
                onClick = onClick,
                enabled = enabled,
            ),
        shape = shape,
        color = if (enabled) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        },
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 0.2f else 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(32.dp),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                }
            )
        }
    }
}