// presentation/ui/components/Icons.kt
package com.example.chessmentor.presentation.ui.components

import androidx.compose.foundation.painter.rememberVectorPainter
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.chessmentor.R

/** Кастомные иконки в стиле ChessMentor для замены Material Design иконок. */

fun MentorIconHome(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_home)),
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

fun MentorIconGames(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_games)),
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

fun MentorIconTraining(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_training)),
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

fun MentorIconStatistics(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_statistics)),
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

fun MentorIconSettings(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_settings)),
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

fun MentorIconClose(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_close)),
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

fun MentorIconCheckCircle(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_check_circle)),
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

fun MentorIconWarning(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_warning)),
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

fun MentorIconInfo(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_info)),
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

fun MentorIconSearch(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_search)),
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

fun MentorIconUpload(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_upload)),
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

fun MentorIconDownload(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_download)),
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

fun MentorIconLock(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_lock)),
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

fun MentorIconPerson(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_person)),
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

fun MentorIconMoreVert(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_more_vert)),
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

fun MentorIconRefresh(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_refresh)),
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

fun MentorIconEmojiEvents(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_emoji_events)),
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

fun MentorIconPsychology(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_psychology)),
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

fun MentorIconArrowBack(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_arrow_back)),
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

fun MentorIconArrowForward(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_arrow_forward)),
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

fun MentorIconVisibility(painter: Painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_visibility)),
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
