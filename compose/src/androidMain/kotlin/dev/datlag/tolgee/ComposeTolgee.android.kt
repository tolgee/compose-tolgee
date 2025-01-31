package dev.datlag.tolgee

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext

@Composable
fun Tolgee.stringResource(@StringRes id: Int, vararg formatArgs: Any): String {
    val context = LocalContext.current
    val key = TolgeeAndroid.getKeyFromRes(context, id)

    val cached = (this as? TolgeeAndroid)?.getStringFromCache(context, id, *formatArgs)
        ?: (key ?: TolgeeAndroid.getKeyFromRes(context, id))?.let {
            this.translationFromCache(it, *formatArgs)
        }

    return produceState(cached ?: androidx.compose.ui.res.stringResource(id, *formatArgs)) {
        value = (this@stringResource as? TolgeeAndroid)?.getString(context, id, *formatArgs)
            ?: (key ?: TolgeeAndroid.getKeyFromRes(context, id))?.let {
                this@stringResource.translation(it, *formatArgs)
            } ?: (this@stringResource as? TolgeeAndroid)?.getStringFromCache(context, id, *formatArgs)
            ?: (key ?: TolgeeAndroid.getKeyFromRes(context, id))?.let {
                this@stringResource.translationFromCache(it, *formatArgs)
            } ?: value
    }.value
}

@Composable
fun stringResource(tolgee: Tolgee? = Tolgee.instance, @StringRes id: Int, vararg formatArgs: Any): String {
    val instance = tolgee ?: Tolgee.instance ?: return androidx.compose.ui.res.stringResource(id, *formatArgs)

    return instance.stringResource(id, *formatArgs)
}