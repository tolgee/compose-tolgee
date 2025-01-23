package dev.datlag.tolgee.extension

import dev.datlag.tolgee.model.push.Mode
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

open class PushExtension(objectFactory: ObjectFactory) : BaseTolgeeExtension(objectFactory) {

    open val forceMode: Property<Mode> = objectFactory.property(Mode::class.java)

    open val languages: SetProperty<String> = objectFactory.setProperty(String::class.java)

    open val namespaces: SetProperty<String> = objectFactory.setProperty(String::class.java)

    override fun setupConvention(project: Project, inherit: BaseTolgeeExtension?) {
        super.setupConvention(project, inherit)

        format.convention(null)
        forceMode.convention(configuration.map {
            it.push?.forceMode ?: Mode.NoForce
        })
        languages.convention(configuration.map {
            it.push?.languages ?: emptyList()
        })
        namespaces.convention(configuration.map {
            it.push?.namespaces ?: emptyList()
        })
    }
}