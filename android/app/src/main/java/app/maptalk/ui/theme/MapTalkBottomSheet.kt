package app.maptalk.ui.theme

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/**
 * Modal sheet sized like iOS 26 floating detents: side + bottom inset so the map peeks
 * around a fully rounded card. Shared by map thread/account sheets and nested pickers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapTalkBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    containerColor: Color,
    contentColor: Color = MapTalkColors.Text,
    dragHandle: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(MapTalkSheets.Corner),
    content: @Composable ColumnScope.() -> Unit,
) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MapTalkSheets.Inset)
            .padding(bottom = MapTalkSheets.Inset + navBottom),
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        dragHandle = dragHandle,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        content = content,
    )
}
