package hk.uwu.soundman.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import hk.uwu.soundman.R
import hk.uwu.soundman.log.AppLog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val HomeOnGlass = Color.White.copy(alpha = 0.88f)
private val HomeMuted = Color.White.copy(alpha = 0.55f)
private val InactiveBannerFill = Color(0xB8E53935)
private val InactiveBannerTitle = Color.White.copy(alpha = 0.95f)
private val InactiveBannerHint = Color.White.copy(alpha = 0.82f)

@Composable
fun HomeScreen(
    onOpenOverlay: () -> Unit,
) {
    val context = LocalContext.current
    val about = remember(context) { AppAboutInfo.load(context) }
    var xposed by remember { mutableStateOf(XposedStatusInfo.load()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                xposed = XposedStatusInfo.load()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    MiuixTheme {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            if (!xposed.active) {
                InactiveBanner(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .padding(top = 16.dp),
                )
            }
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = maxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IdentityCard(context = context, about = about)
                    Spacer(Modifier.height(14.dp))
                    VersionCard(about = about)
                    Spacer(Modifier.height(14.dp))
                    OverlayEntryButton(onClick = onOpenOverlay)
                    Spacer(Modifier.height(14.dp))
                    GithubEntryButton(url = about.githubUrl)
                }
            }
        }
    }
}

@Composable
private fun InactiveBanner(modifier: Modifier = Modifier) {
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        fill = InactiveBannerFill,
        border = Color.White.copy(alpha = 0.18f),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                stringResource(R.string.home_xposed_inactive),
                color = InactiveBannerTitle,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.home_xposed_inactive_hint),
                color = InactiveBannerHint,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun IdentityCard(
    context: Context,
    about: AppAboutInfo,
) {
    val icon = remember(context.packageName) {
        context.packageManager.getApplicationIcon(context.packageName).toBitmap(128, 128)
            .asImageBitmap()
    }
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        fill = OverlayGlassFill,
        border = OverlayGlassBorder,
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                bitmap = icon,
                contentDescription = about.label,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    about.label,
                    color = HomeOnGlass,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.home_author, about.author),
                    color = HomeMuted,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun VersionCard(about: AppAboutInfo) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        fill = OverlayGlassFill,
        border = OverlayGlassBorder,
    ) {
        Column(
            Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AboutInfoLine(
                title = stringResource(R.string.version_codename_label),
                value = about.versionCodename,
            )
            AboutInfoLine(
                title = stringResource(R.string.module_version_label),
                value = about.moduleVersion,
            )
            AboutInfoLine(
                title = stringResource(R.string.home_status_channel),
                value = about.buildChannel,
            )
            AboutInfoLine(
                title = stringResource(R.string.home_git_branch),
                value = about.gitBranch,
            )
        }
    }
}

@Composable
private fun AboutInfoLine(
    title: String,
    value: String,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = HomeMuted, fontSize = 12.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            color = HomeOnGlass,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun OverlayEntryButton(onClick: () -> Unit) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = HomeButtonShape,
        fill = OverlayGlassFill,
        border = OverlayGlassBorder,
        onClick = onClick,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.VolumeUp,
                contentDescription = null,
                tint = HomeOnGlass,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.home_open_overlay),
                color = HomeOnGlass,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun GithubEntryButton(url: String) {
    val context = LocalContext.current
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = HomeButtonShape,
        fill = OverlayGlassFill,
        border = OverlayGlassBorder,
        onClick = { openGithub(context, url) },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_github),
                contentDescription = null,
                tint = HomeOnGlass,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.home_github),
                color = HomeOnGlass,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun openGithub(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }.onFailure { error ->
        AppLog.error("Unable to open GitHub url=$url", error)
    }
}
