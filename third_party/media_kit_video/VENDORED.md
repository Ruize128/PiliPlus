# Vendored media_kit_video

Copied from https://github.com/bggRGjQaUbCoE/media-kit (branch `native`,
commit fd8421d21eade4d6d12de137fa7df902fec20292, path `media_kit_video`), the
revision upstream PiliPlus pins in pubspec.yaml. MIT licensed (see LICENSE).

Left out: `example/`, and the generated `ios/Headers` and
`common/darwin/.cache` (the podspec recreates them during `pod install`).

Local changes:
- iOS frame interpolation (30 → 60 fps) with VideoToolbox's
  `VTLowLatencyFrameInterpolation`: `ios/Classes/plugin/FrameInterpolator.swift`,
  hooks in `ios/Classes/plugin/TextureHW.swift` and
  `common/darwin/Classes/plugin/VideoOutput.swift`.
- Android frame interpolation (2x or 4x) with GPU motion compensation in
  OpenGL ES 3.0: `android/src/main/java/com/alexmercerind/media_kit_video/FrameInterpolator.java`,
  hooks in `VideoOutput.java`, `VideoOutputManager.java` and
  `MediaKitVideoPlugin.java` (`VideoOutputManager.SetFrameInterpolation`), and
  `setFrameInterpolation` in `lib/src/video_controller/platform_video_controller.dart`
  and `android_video_controller/real.dart`.

To update, copy a newer revision over this folder and reapply the changes above.
