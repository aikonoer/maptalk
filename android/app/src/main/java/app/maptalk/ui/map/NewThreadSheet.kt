package app.maptalk.ui.map

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.maptalk.R
import app.maptalk.data.ImageCompressor
import app.maptalk.data.model.PreparedImage
import app.maptalk.data.model.ThreadKind
import app.maptalk.geo.GeoPoint
import app.maptalk.ui.PlaceLabelLine
import app.maptalk.ui.theme.MapTalkColors
import app.maptalk.ui.theme.MapTalkShapes
import app.maptalk.ui.theme.tint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Headline for the map pin + chat header. Stay short so bubbles stay scannable. */
private const val MAX_TITLE_LENGTH = 100

/** Optional opening post (Reddit-style body). Becomes the first message in the chat. */
private const val MAX_BODY_LENGTH = 1_000

/**
 * Starts a conversation at the point the map is centred on.
 * Title = pin headline; optional body and/or photo = first message in the thread.
 */
@Composable
fun NewThreadSheet(
    position: GeoPoint,
    onCreate: (title: String, body: String, kind: ThreadKind, image: PreparedImage?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var bodyText by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ThreadKind.GENERAL) }
    var pendingImage by remember { mutableStateOf<PreparedImage?>(null) }
    var isPreparingImage by remember { mutableStateOf(false) }
    val trimmed = title.trim()
    val trimmedBody = bodyText.trim()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isPreparingImage = true
            val prepared = withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext null
                ImageCompressor.prepare(bytes)
            }
            isPreparingImage = false
            if (prepared != null) pendingImage = prepared
        }
    }

    val previewBitmap = remember(pendingImage) {
        pendingImage?.let { BitmapFactory.decodeByteArray(it.jpegBytes, 0, it.jpegBytes.size) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
    ) {
        Text(
            text = "Start a chat here",
            style = MaterialTheme.typography.titleLarge,
            color = MapTalkColors.Text,
        )

        PlaceLabelLine(
            point = position,
            trailing = "anyone looking here can join in",
            modifier = Modifier.padding(top = 6.dp),
        )

        OutlinedTextField(
            value = title,
            onValueChange = { if (it.length <= MAX_TITLE_LENGTH) title = it },
            placeholder = { Text("Title — what is going on?", color = MapTalkColors.Faint) },
            singleLine = true,
            shape = RoundedCornerShape(MapTalkShapes.Field),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MapTalkColors.Raised,
                unfocusedContainerColor = MapTalkColors.Raised,
                focusedBorderColor = MapTalkColors.Accent,
                unfocusedBorderColor = MapTalkColors.Hairline,
                cursorColor = MapTalkColors.Accent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp),
        )

        // Only worth the space once the limit is close.
        Text(
            text = "${trimmed.length}/$MAX_TITLE_LENGTH",
            style = MaterialTheme.typography.labelSmall,
            color = MapTalkColors.Faint,
            modifier = Modifier
                .padding(top = 6.dp)
                .alpha(if (trimmed.length > MAX_TITLE_LENGTH - 20) 1f else 0f),
        )

        OutlinedTextField(
            value = bodyText,
            onValueChange = { if (it.length <= MAX_BODY_LENGTH) bodyText = it },
            placeholder = { Text("Say what's here (optional)", color = MapTalkColors.Faint) },
            shape = RoundedCornerShape(MapTalkShapes.Field),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MapTalkColors.Raised,
                unfocusedContainerColor = MapTalkColors.Raised,
                focusedBorderColor = MapTalkColors.Accent,
                unfocusedBorderColor = MapTalkColors.Hairline,
                cursorColor = MapTalkColors.Accent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp, max = 140.dp)
                .padding(top = 12.dp),
        )

        Text(
            text = "${trimmedBody.length}/$MAX_BODY_LENGTH",
            style = MaterialTheme.typography.labelSmall,
            color = MapTalkColors.Faint,
            modifier = Modifier
                .padding(top = 6.dp)
                .alpha(if (trimmedBody.length > MAX_BODY_LENGTH - 80) 1f else 0f),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (previewBitmap != null) {
                Box {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = "Opening photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    IconButton(
                        onClick = { pendingImage = null },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(28.dp)
                            .offset(x = 6.dp, y = (-6).dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "Remove photo",
                            tint = Color.White,
                            modifier = Modifier
                                .size(20.dp)
                                .background(MapTalkColors.Faint, CircleShape)
                                .padding(3.dp),
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(MapTalkShapes.Field))
                        .background(MapTalkColors.Raised)
                        .border(1.dp, MapTalkColors.Hairline, RoundedCornerShape(MapTalkShapes.Field))
                        .clickable(enabled = !isPreparingImage) {
                            photoPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                MapTalkColors.Accent.copy(alpha = 0.14f),
                                RoundedCornerShape(10.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isPreparingImage) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = MapTalkColors.Subtle,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_photo),
                                contentDescription = null,
                                tint = MapTalkColors.Accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Show what's here",
                            style = MaterialTheme.typography.labelLarge,
                            color = MapTalkColors.Text,
                        )
                        Text(
                            text = "Photo — same as writing a line",
                            style = MaterialTheme.typography.labelSmall,
                            color = MapTalkColors.Faint,
                        )
                    }
                }
            }
        }

        Text(
            text = "What kind of thing is it?",
            style = MaterialTheme.typography.labelSmall,
            color = MapTalkColors.Subtle,
            modifier = Modifier.padding(top = 14.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThreadKind.entries.forEach { option ->
                KindChip(
                    kind = option,
                    isSelected = option == kind,
                    onClick = { kind = option },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Button(
            onClick = { onCreate(trimmed, trimmedBody, kind, pendingImage) },
            enabled = trimmed.isNotEmpty() && !isPreparingImage,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MapTalkColors.Accent,
                contentColor = Color.White,
                disabledContainerColor = MapTalkColors.Raised,
                disabledContentColor = MapTalkColors.Faint,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp),
        ) {
            Text(
                text = "Pin it and open the chat",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun KindChip(
    kind: ThreadKind,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        if (isSelected) kind.tint.copy(alpha = 0.16f) else MapTalkColors.Raised,
        label = "kindChipContainer",
    )
    val border by animateColorAsState(
        if (isSelected) kind.tint.copy(alpha = 0.5f) else Color.Transparent,
        label = "kindChipBorder",
    )

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(MapTalkShapes.Field),
        color = container,
        border = BorderStroke(1.dp, border),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = kind.glyph, style = MaterialTheme.typography.labelLarge)
            Text(
                text = kind.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) kind.tint else MapTalkColors.Subtle,
                maxLines = 1,
            )
        }
    }
}
