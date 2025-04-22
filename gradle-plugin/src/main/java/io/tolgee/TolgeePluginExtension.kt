package io.tolgee

import io.tolgee.extension.BaseTolgeeExtension
import io.tolgee.extension.CompilerPluginExtension
import io.tolgee.extension.PullExtension
import io.tolgee.extension.PushExtension
import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.Actions
import org.gradle.util.internal.ConfigureUtil
import javax.inject.Inject

open class TolgeePluginExtension @Inject constructor(objectFactory: ObjectFactory) : BaseTolgeeExtension(objectFactory) {

    /**
     * Configuration for handling pull action.
     */
    lateinit var pull: PullExtension
        private set

    /**
     * Configuration for handling push action.
     */
    lateinit var push: PushExtension
        private set

    /**
     * Configuration for handling compiler plugin.
     */
    lateinit var compilerPlugin: CompilerPluginExtension
        private set

    /**
     * Change how the pull action is handled.
     */
    fun pull(closure: Closure<in PullExtension>): PullExtension {
        return pull(ConfigureUtil.configureUsing(closure))
    }

    /**
     * Change how the pull action is handled.
     */
    fun pull(action: Action<in PullExtension>): PullExtension {
        return Actions.with(pull, action)
    }

    /**
     * Change how the push action is handled.
     */
    fun push(closure: Closure<in PushExtension>): PushExtension {
        return push(ConfigureUtil.configureUsing(closure))
    }

    /**
     * Change how the push action is handled.
     */
    fun push(action: Action<in PushExtension>): PushExtension {
        return Actions.with(push, action)
    }

    /**
     * Change how the compiler plugin is handled.
     */
    fun compilerPlugin(closure: Closure<in CompilerPluginExtension>): CompilerPluginExtension {
        return compilerPlugin(ConfigureUtil.configureUsing(closure))
    }

    /**
     * Change how the compiler plugin is handled.
     */
    fun compilerPlugin(action: Action<in CompilerPluginExtension>): CompilerPluginExtension {
        return Actions.with(compilerPlugin, action)
    }

    override fun setupConvention(project: Project, inherit: BaseTolgeeExtension?) {
        super.setupConvention(project, inherit)

        pull = PullExtension(project.objects).also {
            it.setupConvention(project, this)
        }
        push = PushExtension(project.objects).also {
            it.setupConvention(project, this)
        }
        compilerPlugin = CompilerPluginExtension(project.objects).also {
            it.setupConvention(project)
        }
    }

}