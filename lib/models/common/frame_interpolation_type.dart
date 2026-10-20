import 'package:PiliPlus/models/common/enum_with_label.dart';

/// 补帧: 24–30 fps to 60 fps on iOS 26+, see
/// third_party/media_kit_video/ios/Classes/plugin/FrameInterpolator.swift.
enum FrameInterpolationType with EnumWithLabel {
  disable('禁用', '0'),
  efficiency('效率', '720'),
  quality('画质', '1080'),
  ;

  @override
  final String label;

  /// The value of the mpv property `user-data/frame-interpolation`: the
  /// largest size frames are interpolated at.
  final String mpvValue;
  const FrameInterpolationType(this.label, this.mpvValue);
}
