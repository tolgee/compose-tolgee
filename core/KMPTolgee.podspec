Pod::Spec.new do |spec|
    spec.name                     = 'KMPTolgee'
    spec.version                  = '1.0.0-alpha03'
    spec.homepage                 = 'https://github.com/tolgee/tolgee-mobile-kotlin-sdk'
    spec.source                   = { :http=> ''}
    spec.authors                  = ''
    spec.license                  = 'Apache License 2.0'
    spec.summary                  = 'Kotlin Multiplatform localization wrapper for Tolgee'
    spec.vendored_frameworks      = 'build/cocoapods/framework/KMPTolgee.framework'
    spec.libraries                = 'c++'
                
                
                
    if !Dir.exist?('build/cocoapods/framework/KMPTolgee.framework') || Dir.empty?('build/cocoapods/framework/KMPTolgee.framework')
        raise "

        Kotlin framework 'KMPTolgee' doesn't exist yet, so a proper Xcode project can't be generated.
        'pod install' should be executed after running ':generateDummyFramework' Gradle task:

            ./gradlew :core:generateDummyFramework

        Alternatively, proper pod installation is performed during Gradle sync in the IDE (if Podfile location is set)"
    end
                
    spec.xcconfig = {
        'ENABLE_USER_SCRIPT_SANDBOXING' => 'NO',
    }
                
    spec.pod_target_xcconfig = {
        'KOTLIN_PROJECT_PATH' => ':core',
        'PRODUCT_MODULE_NAME' => 'KMPTolgee',
    }
                
    spec.script_phases = [
        {
            :name => 'Build KMPTolgee',
            :execution_position => :before_compile,
            :shell_path => '/bin/sh',
            :script => <<-SCRIPT
                if [ "YES" = "$OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED" ]; then
                  echo "Skipping Gradle build task invocation due to OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED environment variable set to \"YES\""
                  exit 0
                fi
                set -ev
                REPO_ROOT="$PODS_TARGET_SRCROOT"
                "$REPO_ROOT/../gradlew" -p "$REPO_ROOT" $KOTLIN_PROJECT_PATH:syncFramework \
                    -Pkotlin.native.cocoapods.platform=$PLATFORM_NAME \
                    -Pkotlin.native.cocoapods.archs="$ARCHS" \
                    -Pkotlin.native.cocoapods.configuration="$CONFIGURATION"
            SCRIPT
        }
    ]
                
end