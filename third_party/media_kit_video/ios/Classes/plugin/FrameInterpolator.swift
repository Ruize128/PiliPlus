import CoreMedia
import CoreVideo
import Flutter
import QuartzCore
import VideoToolbox

/// Doubles the frame rate of 24–30 fps video with VideoToolbox's low-latency
/// frame interpolation (iOS 26+). Each frame mpv renders is shown after a
/// frame interpolated between it and the previous one, paced at the display's
/// refresh by a display link, so playback runs about one frame behind.
///
/// Dart turns it on with the mpv property `user-data/frame-interpolation`:
/// "720" or "1080" caps the size the frames are interpolated at; "0" or unset
/// turns it off. The state goes to `user-data/frame-interpolation-status`:
/// off, unsupported, loading, active or bypass (the source is already fast).
@available(iOS 26.0, *)
final class FrameInterpolator {
  private let handle: OpaquePointer
  private let texture: TextureHW
  private let registry: FlutterTextureRegistry
  private let textureId: () -> Int64

  init(
    handle: OpaquePointer,
    texture: TextureHW,
    registry: FlutterTextureRegistry,
    textureId: @escaping () -> Int64
  ) {
    self.handle = handle
    self.texture = texture
    self.registry = registry
    self.textureId = textureId
  }

  // MARK: Render worker

  private var status = ""
  private var rawLimit: String?
  private var active: Session?
  private var loadingSize: Size?
  private var previous: CVPixelBuffer?
  private var lastArrival: CFTimeInterval = 0
  private var frameInterval: Double = 1.0 / 30
  private var outputPool: (Size, CVPixelBufferPool)?
  private lazy var transfer: VTPixelTransferSession? = {
    var session: VTPixelTransferSession?
    guard VTPixelTransferSessionCreate(
      allocator: nil, pixelTransferSessionOut: &session) == noErr, let session
    else { return nil }
    VTSessionSetProperty(session, key: kVTPixelTransferPropertyKey_RealTime, value: kCFBooleanTrue)
    VTSessionSetProperty(
      session, key: kVTPixelTransferPropertyKey_DestinationYCbCrMatrix,
      value: kCVImageBufferYCbCrMatrix_ITU_R_709_2)
    return session
  }()
  private var stats = Stats()

  /// Called on the render worker after each frame mpv renders. True: the
  /// interpolator will present this frame, after the one interpolated before
  /// it. False: present it the usual way.
  func takeOver(_ frame: CVPixelBuffer) -> Bool {
    let limit = readLimit()
    if limit <= 0 {
      dropSessions()
      setStatus("off")
      return release()
    }
    guard VTLowLatencyFrameInterpolationConfiguration.isSupported else {
      setStatus("unsupported")
      return release()
    }

    let now = CACurrentMediaTime()
    let interval = now - lastArrival
    lastArrival = now
    // A pause, seek or stall: nothing to interpolate across.
    let continuous = interval < 0.1
    if continuous {
      frameInterval += 0.2 * (interval - frameInterval)
    }

    let width = CVPixelBufferGetWidth(frame)
    let height = CVPixelBufferGetHeight(frame)
    guard let session = session(for: interpolationSize(width, height, limit)) else {
      previous = nil
      return release()
    }
    // 40 fps and faster sources need no help.
    if frameInterval < 0.025 {
      previous = nil
      setStatus("bypass")
      return release()
    }

    guard
      let transfer,
      let shown = makeOutput(width, height),
      VTPixelTransferSessionTransferImage(transfer, from: frame, to: shown) == noErr,
      let current = session.makeSource(),
      VTPixelTransferSessionTransferImage(transfer, from: frame, to: current) == noErr
    else {
      previous = nil
      return release()
    }
    tag709(current)

    var frames: [CVPixelBuffer] = []
    if continuous, let previous, let middle = session.interpolate(previous, current, stats: &stats),
      let middleShown = makeOutput(width, height)
    {
      tag709(middle)
      if VTPixelTransferSessionTransferImage(transfer, from: middle, to: middleShown) == noErr {
        frames.append(middleShown)
      }
    }
    frames.append(shown)
    previous = current
    stats.frames += 1
    stats.report(size: session.size)
    setStatus("active")
    present(frames)
    return true
  }

  func stop() {
    _ = release()
    dropSessions()
  }

  /// The largest size to interpolate at, from the mpv property: "720"/"1080".
  private func readLimit() -> Int {
    var raw = "<unset>"
    if let value = mpv_get_property_string(handle, "user-data/frame-interpolation") {
      raw = String(cString: value)
      mpv_free(value)
    }
    if raw != rawLimit {
      rawLimit = raw
      print("[frameinterp] setting \(raw)")
      fflush(stdout)
    }
    // user-data holds mpv nodes, which read back as JSON: "720" with quotes.
    return Int(raw.filter(\.isNumber)) ?? 0
  }

  private func setStatus(_ value: String) {
    guard value != status else { return }
    status = value
    mpv_set_property_string(handle, "user-data/frame-interpolation-status", value)
    print("[frameinterp] \(value)")
    fflush(stdout)
  }

  /// [width]x[height] scaled to fit the cap and the processor's limits.
  private func interpolationSize(_ width: Int, _ height: Int, _ limit: Int) -> Size {
    var maxDimension = limit >= 1080 ? 1920 : 1280
    var maxPixels = limit >= 1080 ? 1920 * 1080 : 1280 * 720
    if #available(iOS 27.0, *) {
      if let value = VTLowLatencyFrameInterpolationConfiguration.maximumDimension(
        forSpatialScaleFactor: 1), value > 0
      {
        maxDimension = min(maxDimension, value)
      }
      if let value = VTLowLatencyFrameInterpolationConfiguration.maximumPixelCount(
        forSpatialScaleFactor: 1), value > 0
      {
        maxPixels = min(maxPixels, value)
      }
    }
    let scale = min(
      1,
      (Double(maxPixels) / Double(width * height)).squareRoot(),
      Double(maxDimension) / Double(max(width, height)))
    return Size(
      width: max(2, Int(Double(width) * scale) & ~1),
      height: max(2, Int(Double(height) * scale) & ~1))
  }

  /// The session for [size], loading it in the background if needed (the
  /// model takes a second or two): nil until it is ready.
  private func session(for size: Size) -> Session? {
    if let active, active.size == size { return active }
    sessionLock.lock()
    let ready = loaded
    let failed = failedSize
    sessionLock.unlock()
    if let ready, ready.size == size {
      active = ready
      return ready
    }
    if failed == size {
      setStatus("unsupported")
      return nil
    }
    setStatus("loading")
    if loadingSize != size {
      loadingSize = size
      active = nil
      loader.async { [weak self] in
        let session = Session(size: size)
        guard let self else { return }
        self.sessionLock.lock()
        if let session {
          self.loaded = session
        } else {
          self.failedSize = size
        }
        self.sessionLock.unlock()
      }
    }
    return nil
  }

  private func dropSessions() {
    active = nil
    loadingSize = nil
    previous = nil
    sessionLock.lock()
    loaded = nil
    failedSize = nil
    sessionLock.unlock()
  }

  private func makeOutput(_ width: Int, _ height: Int) -> CVPixelBuffer? {
    let size = Size(width: width, height: height)
    if outputPool?.0 != size {
      let attributes: [String: Any] = [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        kCVPixelBufferWidthKey as String: width,
        kCVPixelBufferHeightKey as String: height,
        kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any](),
        kCVPixelBufferMetalCompatibilityKey as String: true,
      ]
      var pool: CVPixelBufferPool?
      CVPixelBufferPoolCreate(nil, nil, attributes as CFDictionary, &pool)
      guard let pool else { return nil }
      outputPool = (size, pool)
    }
    return makeBuffer(outputPool!.1)
  }

  private func tag709(_ buffer: CVPixelBuffer) {
    CVBufferSetAttachment(
      buffer, kCVImageBufferYCbCrMatrixKey, kCVImageBufferYCbCrMatrix_ITU_R_709_2,
      .shouldPropagate)
  }

  // MARK: Session loading

  private let loader = DispatchQueue(label: "media_kit_video.frame_interpolation")
  private let sessionLock = NSLock()
  private var loaded: Session?
  private var failedSize: Size?

  // MARK: Presentation (main thread)

  private let queueLock = NSLock()
  private var queue: [CVPixelBuffer] = []
  private var presenting = false
  private var displayLink: CADisplayLink?

  private func present(_ frames: [CVPixelBuffer]) {
    queueLock.lock()
    let started = presenting
    presenting = true
    queue.append(contentsOf: frames)
    // Fell behind: skip ahead rather than add latency.
    if queue.count > 4 { queue.removeFirst(queue.count - 2) }
    queueLock.unlock()
    if !started {
      DispatchQueue.main.async { [weak self] in self?.startDisplayLink() }
    }
  }

  /// Back to mpv's frames: shows the current frame on the usual path.
  private func release() -> Bool {
    queueLock.lock()
    let wasPresenting = presenting
    presenting = false
    queue.removeAll()
    texture.present(nil)
    queueLock.unlock()
    if wasPresenting {
      DispatchQueue.main.async { [weak self] in self?.stopDisplayLink() }
    }
    return false
  }

  private func startDisplayLink() {
    guard displayLink == nil else { return }
    let target = DisplayLinkTarget()
    target.owner = self
    let link = CADisplayLink(target: target, selector: #selector(DisplayLinkTarget.tick))
    link.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 60, preferred: 60)
    link.add(to: .main, forMode: .common)
    displayLink = link
  }

  private func stopDisplayLink() {
    queueLock.lock()
    let presenting = self.presenting
    queueLock.unlock()
    guard !presenting else { return }
    displayLink?.invalidate()
    displayLink = nil
  }

  fileprivate func tick() {
    queueLock.lock()
    guard presenting, !queue.isEmpty else {
      queueLock.unlock()
      return
    }
    texture.present(queue.removeFirst())
    queueLock.unlock()
    registry.textureFrameAvailable(textureId())
  }

  deinit {
    let link = displayLink
    DispatchQueue.main.async { link?.invalidate() }
  }
}

private struct Size: Equatable {
  let width: Int
  let height: Int
}

/// One VideoToolbox interpolation session at a fixed size.
@available(iOS 26.0, *)
private final class Session {
  let size: Size
  private let processor = VTFrameProcessor()
  private let sourcePool: CVPixelBufferPool
  private let destinationPool: CVPixelBufferPool
  private var timestamp: Int64 = 0

  init?(size: Size) {
    self.size = size
    guard
      let configuration = VTLowLatencyFrameInterpolationConfiguration(
        frameWidth: size.width, frameHeight: size.height, numberOfInterpolatedFrames: 1)
    else { return nil }
    var source: CVPixelBufferPool?
    var destination: CVPixelBufferPool?
    CVPixelBufferPoolCreate(
      nil, nil, configuration.sourcePixelBufferAttributes as CFDictionary, &source)
    CVPixelBufferPoolCreate(
      nil, nil, configuration.destinationPixelBufferAttributes as CFDictionary, &destination)
    guard let source, let destination else { return nil }
    sourcePool = source
    destinationPool = destination
    do {
      try processor.startSession(configuration: configuration)
    } catch {
      print("[frameinterp] \(size.width)x\(size.height): \(error)")
      return nil
    }
  }

  deinit {
    processor.endSession()
  }

  func makeSource() -> CVPixelBuffer? { makeBuffer(sourcePool) }

  /// The frame halfway between [previous] and [current].
  func interpolate(
    _ previous: CVPixelBuffer, _ current: CVPixelBuffer, stats: inout Stats
  ) -> CVPixelBuffer? {
    guard
      CVPixelBufferGetWidth(previous) == size.width,
      CVPixelBufferGetHeight(previous) == size.height,
      let destination = makeBuffer(destinationPool),
      let previousFrame = VTFrameProcessorFrame(
        buffer: previous, presentationTimeStamp: CMTime(value: timestamp, timescale: 60)),
      let currentFrame = VTFrameProcessorFrame(
        buffer: current, presentationTimeStamp: CMTime(value: timestamp + 2, timescale: 60)),
      let destinationFrame = VTFrameProcessorFrame(
        buffer: destination, presentationTimeStamp: CMTime(value: timestamp + 1, timescale: 60)),
      let parameters = VTLowLatencyFrameInterpolationParameters(
        sourceFrame: currentFrame, previousFrame: previousFrame,
        interpolationPhase: [0.5], destinationFrames: [destinationFrame])
    else { return nil }
    timestamp += 2

    let started = CACurrentMediaTime()
    let done = DispatchSemaphore(value: 0)
    var failure: Error?
    processor.process(parameters: parameters) { _, error in
      failure = error
      done.signal()
    }
    // Late frames are worse than missing ones.
    guard done.wait(timeout: .now() + .milliseconds(60)) == .success, failure == nil else {
      stats.failed += 1
      return nil
    }
    stats.interpolated += 1
    stats.milliseconds += (CACurrentMediaTime() - started) * 1000
    return destination
  }
}

private func makeBuffer(_ pool: CVPixelBufferPool) -> CVPixelBuffer? {
  var buffer: CVPixelBuffer?
  CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer)
  return buffer
}

/// Logged every 5 s while active (visible with `devicectl … --console`).
private struct Stats {
  var frames = 0
  var interpolated = 0
  var failed = 0
  var milliseconds = 0.0
  var since = CACurrentMediaTime()

  mutating func report(size: Size) {
    let elapsed = CACurrentMediaTime() - since
    guard elapsed >= 5 else { return }
    print(String(
      format: "[frameinterp] %dx%d: in %.1f fps, out %.1f fps, interpolation %.1f ms avg, %d failed",
      size.width, size.height, Double(frames) / elapsed,
      Double(frames + interpolated) / elapsed,
      interpolated == 0 ? 0 : milliseconds / Double(interpolated), failed))
    fflush(stdout)
    self = Stats()
  }
}

private final class DisplayLinkTarget: NSObject {
  @available(iOS 26.0, *)
  weak var owner: FrameInterpolator? {
    get { _owner as? FrameInterpolator }
    set { _owner = newValue }
  }
  private weak var _owner: AnyObject?

  @objc func tick() {
    if #available(iOS 26.0, *) {
      owner?.tick()
    }
  }
}
