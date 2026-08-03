Pod::Spec.new do |spec|
  spec.name = 'OneSignalKMP'
  spec.version = ENV.fetch('ONESIGNAL_KMP_VERSION', '0.0.0')
  spec.summary = 'Shared Kotlin Multiplatform code for OneSignal SDKs'
  spec.homepage = 'https://github.com/OneSignal/OneSignal-KMP-SDK'
  spec.license = { type: 'MIT', file: 'LICENSE' }
  spec.author = { 'OneSignal' => 'support@onesignal.com' }
  spec.source = {
    git: 'https://github.com/OneSignal/OneSignal-KMP-SDK.git',
    tag: "v#{spec.version}",
  }

  spec.ios.deployment_target = '12.0'
  spec.vendored_frameworks = 'kmp/build/XCFrameworks/release/OneSignalKMP.xcframework'
  spec.prepare_command = './gradlew :kmp:assembleOneSignalKMPReleaseXCFramework --console=plain'
end
