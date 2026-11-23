// presentation/ui/components/Icons.kt
package com.example.chessmentor.presentation.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.chessmentor.R

/** Кастомные иконки в стиле ChessMentor для замены Material Design иконок. */

@Composable
fun MentorIconHome(painter: Painter = painterResource(R.drawable.ic_mentor_home)),
                   tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                   size: Dp = 24.dp,
                   modifier: Modifier = Modifier,
                   contentDescription: String? = "Главная") {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun MentorIconGames(painter: Painter = painterResource(R.drawable.ic_mentor_games)),
                    tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    size: Dp = 24.dp,
                    modifier: Modifier = Modifier,
                    contentDescription: String? = "Партии") {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun MentorIconTraining(painter: Painter = painterResource(R.drawable.ic_mentor_training)),
                       tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                       size: Dp = 24.dp,
                       modifier: Modifier = Modifier,
                       contentDescription: String? = "Задачи") {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun MentorIconStatistics(painter: Painter = painterResource(R.drawable.ic_mentor_statistics)),
                         tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                         size: Dp = 24.dp,
                         modifier: Modifier = Modifier,
                         contentDescription: String? = "Статистика") {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun MentorIconSettings(painter: Painter = painterResource(R.drawable.ic_mentor_settings)),
                       tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                       size: Dp = 24.dp,
                       modifier: Modifier = Modifier,
                       contentDescription: String? = "Настройки") {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun MentorIconClose(painter: Painter = painterResource(R.drawable.ic_mentor_close)),
                    tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    size: Dp = 24.dp,
                    modifier: Modifier = Modifier,
                    contentDescription: String? = "Закрыть") {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun MentorIconCheckCircle(painter: Painter = painterResource(R.drawable.ic_mentor_check_circle)),
                          tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                          size: Dp = 24.dp,
                          modifier: Modifier = Modifier,
                          contentDescription: String? = null) {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun MentorIconWarning(painter: Painter = painterResource(R.drawable.ic_mentor_warning)),
                      tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.caution,
                      size: Dp = 24.dp,
                      modifier: Modifier = Modifier,
                      contentDescription: String? = null) {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun MentorIconInfo(painter: Painter = painterResource(R.drawable.ic_mentor_info)),
                   tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                   size: Dp = 24.dp,
                   modifier: Modifier = Modifier,
                   contentDescription: String? = null) {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun MentorIconSearch(painter: Painter = painterResource(R.drawable.ic_mentor_search)),
                     tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                     size: Dp = 20.dp,
                     modifier: Modifier = Modifier,
                     contentDescription: String? = "Поиск") {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun MentorIconUpload(painter: Painter = painterResource(R.drawable.ic_mentor_upload)),
                     tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                     size: Dp = 24.dp,
                     modifier: Modifier = Modifier,
                     contentDescription: String? = "Загрузить") {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun MentorIconDownload(painter: Painter = painterResource(R.drawable.ic_mentor_download)),
                       tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                       size: Dp = 24.dp,
                       modifier: Modifier = Modifier,
                       contentDescription: String? = "Скачать") {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun MentorIconLock(painter: Painter = painterResource(R.drawable.ic_mentor_lock)),
                   tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                   size: Dp = 24.dp,
                   modifier: Modifier = Modifier,
                   contentDescription: String? = null) {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun MentorIconPerson(painter: Painter = painterResource(R.drawable.ic_mentor_person)),
                     tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                     size: Dp = 24.dp,
                     modifier: Modifier = Modifier,
                     contentDescription: String? = null) {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun MentorIconMoreVert(painter: Painter = painterResource(R.drawable.ic_mentor_more_vert)),
                       tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                       size: Dp = 24.dp,
                       modifier: Modifier = Modifier,
                       contentDescription: String? = "Ещё") {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun MentorIconRefresh(painter: Painter = painterResource(R.drawable.ic_mentor_refresh)),
                      tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                      size: Dp = 24.dp,
                      modifier: Modifier = Modifier,
                      contentDescription: String? = "Обновить") {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun MentorIconEmojiEvents(painter: Painter = painterResource(R.drawable.ic_mentor_emoji_events)),
                          tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                          size: Dp = 24.dp,
                          modifier: Modifier = Modifier,
                          contentDescription: String? = null) {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun MentorIconPsychology(painter: Painter = painterResource(R.drawable.ic_mentor_psychology)),
                         tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                         size: Dp = 24.dp,
                         modifier: Modifier = Modifier,
                         contentDescription: String? = null) {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun MentorIconArrowBack(painter: Painter = painterResource(R.drawable.ic_mentor_arrow_back)),
                        tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                        size: Dp = 24.dp,
                        modifier: Modifier = Modifier,
                        contentDescription: String? = "Назад") {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun MentorIconArrowForward(painter: Painter = painterResource(R.drawable.ic_mentor_arrow_forward)),
                           tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                           size: Dp = 24.dp,
                           modifier: Modifier = Modifier,
                           contentDescription: String? = "Вперёд") {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun MentorIconVisibility(painter: Painter = painterResource(R.drawable.ic_mentor_visibility)),
                         tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                         size: Dp = 24.dp,
                         modifier: Modifier = Modifier,
                         contentDescription: String? = null) {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}
