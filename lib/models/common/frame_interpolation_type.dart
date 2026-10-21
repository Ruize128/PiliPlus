import 'package:PiliPlus/models/common/enum_with_label.dart';

/// 补帧: 24–30 fps to 60 fps (or 120 fps, see [FrameInterpolationMultiplier]),
/// done in third_party/media_kit_video by FrameInterpolator.swift (iOS 26+)
/// and FrameInterpolator.java (Android).
enum FrameInterpolationType with EnumWithLabel {
  disable('禁用', '0'),
  efficiency('效率', '720'),
  quality('画质', '1080'),
  ;

  @override
  final String label;

  /// iOS: the value of the mpv property `user-data/frame-interpolation`, the
  /// largest size frames are interpolated at. Android: 1080 finds motion at
  /// the video's own resolution, otherwise at most 540p.
  final String mpvValue;
  const FrameInterpolationType(this.label, this.mpvValue);
}

/// 补帧倍数 (Android): output frames per source frame. 4x only shows on a
/// 120 Hz screen; the player asks for the highest refresh rate while it is on.
enum FrameInterpolationMultiplier with EnumWithLabel {
  x2('2x', 2),
  x4('4x', 4),
  ;

  @override
  final String label;
  final int value;
  const FrameInterpolationMultiplier(this.label, this.value);
}
