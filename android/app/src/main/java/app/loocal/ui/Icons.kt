package app.loocal.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.ui.graphics.vector.ImageVector

/** Persona icons: iOS SF Symbol names mapped to the closest Material icons. */
fun personaIcon(name: String): ImageVector = when (name) {
    "brain.head.profile" -> Icons.Outlined.Psychology
    "book.closed" -> Icons.Outlined.MenuBook
    "pencil.line" -> Icons.Outlined.Edit
    "briefcase" -> Icons.Outlined.WorkOutline
    "heart" -> Icons.Outlined.FavoriteBorder
    "leaf" -> Icons.Outlined.Eco
    "flame" -> Icons.Outlined.LocalFireDepartment
    "moon" -> Icons.Outlined.DarkMode
    "cup.and.saucer" -> Icons.Outlined.Coffee
    "graduationcap" -> Icons.Outlined.School
    "theatermasks" -> Icons.Outlined.TheaterComedy
    "terminal" -> Icons.Outlined.Terminal
    "globe" -> Icons.Outlined.Public
    "music.note" -> Icons.Outlined.MusicNote
    "camera" -> Icons.Outlined.PhotoCamera
    else -> Icons.Outlined.AutoAwesome // "sparkles"
}

/** iOS SF Symbol names used by connectors and tool boxes, mapped to Material icons. */
fun symbolIcon(name: String): ImageVector = when (name) {
    "calendar" -> Icons.Outlined.CalendarMonth
    "calendar.badge.plus" -> Icons.Outlined.EditCalendar
    "checklist" -> Icons.Outlined.Checklist
    "doc.on.clipboard" -> Icons.Outlined.ContentPaste
    "globe" -> Icons.Outlined.Public
    "magnifyingglass" -> Icons.Outlined.Search
    "server.rack" -> Icons.Outlined.Dns
    "function" -> Icons.Outlined.Functions
    "character.bubble" -> Icons.Outlined.Translate
    "car" -> Icons.Outlined.DirectionsCar
    "mappin.and.ellipse" -> Icons.Outlined.Place
    "doc.text" -> Icons.Outlined.Article
    "brain" -> Icons.Outlined.Psychology
    "point.3.connected.trianglepath.dotted" -> Icons.Outlined.Hub
    else -> Icons.Outlined.AutoAwesome
}
