#if DEBUG
import FlareAppleUI
import KotlinSharedUI
import SwiftUI
import UIKit

/// Exercise the real timeline under the same collapsing bars as Home.
struct TimelineMediaScrollTestFixture: View {
    var body: some View {
        if #available(iOS 26.0, *) {
            TabView {
                Tab("Home", systemImage: "house") {
                    NavigationStack {
                        TimelineMediaScrollTestView()
                            .ignoresSafeArea(edges: .vertical)
                            .navigationTitle("Timeline")
                            .navigationBarTitleDisplayMode(.inline)
                            .modifier(ScrollMinimizingNavigationBar())
                    }
                }
                Tab("Other", systemImage: "person") { Text("Other") }
            }
            .tabBarMinimizeBehavior(.onScrollDown)
        } else {
            TimelineMediaScrollTestView()
        }
    }
}

private struct TimelineMediaScrollTestView: UIViewControllerRepresentable {
    func makeCoordinator() -> TimelineScrollMeasurements { TimelineScrollMeasurements() }

    func makeUIViewController(context: Context) -> UITimelineCollectionViewController {
        context.coordinator.controller
    }

    func updateUIViewController(_ controller: UITimelineCollectionViewController, context: Context) {}

    static func dismantleUIViewController(_ controller: UITimelineCollectionViewController, coordinator: TimelineScrollMeasurements) {
        coordinator.displayLink?.invalidate()
    }
}

private final class CarouselTouchProbe: UIGestureRecognizer, UIGestureRecognizerDelegate {
    private let onTouchBegan: () -> Void

    init(onTouchBegan: @escaping () -> Void) {
        self.onTouchBegan = onTouchBegan
        super.init(target: nil, action: nil)
        cancelsTouchesInView = false
        delaysTouchesBegan = false
        delaysTouchesEnded = false
        delegate = self
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        onTouchBegan()
        state = .failed
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        true
    }
}

private final class TimelineScrollMeasurements: NSObject {
    let controller = UITimelineCollectionViewController(detailStatusKey: nil)
    var displayLink: CADisplayLink?
    private let usesCarousel = ProcessInfo.processInfo.arguments.contains("--timeline-media-carousel")
    private var list: TimelineCollectionView {
        controller.view.subviews.compactMap { $0 as? TimelineCollectionView }.first!
    }
    private let label = UILabel()
    private var previous: (offset: CGFloat, pan: CGFloat?, frames: [String: CGRect])?
    private var samples = 0
    private var maxError: CGFloat = 0
    private var maxHeightChange: CGFloat = 0
    private var minTopInset = CGFloat.greatestFiniteMagnitude
    private var maxTopInset: CGFloat = 0
    private weak var observedCarousel: UICollectionView?
    private var activelyDecelerating = false
    private var pendingDecelerationCatch = false
    private var touchesDuringDeceleration = 0
    private var decelerationCatchDrags = 0
    private var decelerationTouchOffsetX: CGFloat = 0

    override init() {
        super.init()
        let helper = TimelinePresentationAppearancePatchHelper.shared
        var patch = helper.setLimitSingleMediaHeight(patch: helper.empty, value: true)
        if usesCarousel {
            patch = helper.setMediaLayout(patch: patch, value: .carousel)
        }
        controller.appearance = TimelineUIKitAppearance(timeline: helper.resolve(
            base: TimelineAppearance.companion.Default,
            patch: patch))
        var input = TimelineContent()
        input.state = .loaded
        input.items = (0..<100).map {
            .post(makeTimelineTestRow(
                $0,
                imageHeight: usesCarousel ? 600 : 1_600,
                mediaCount: usesCarousel ? 16 : 1,
                textRepeatCount: $0 % 4
            ))
        }
        controller.submit(input, columns: 1)
        controller.loadViewIfNeeded()

        let reset = UIButton(type: .system)
        reset.setTitle("Reset measurements", for: .normal)
        reset.accessibilityIdentifier = "timeline-scroll-reset"
        reset.addAction(UIAction { [weak self] _ in self?.reset() }, for: .touchUpInside)
        reset.translatesAutoresizingMaskIntoConstraints = false
        controller.view.addSubview(reset)
        label.accessibilityIdentifier = "timeline-scroll-metrics"
        label.isAccessibilityElement = true
        label.font = .systemFont(ofSize: 8)
        label.numberOfLines = 2
        label.backgroundColor = .systemBackground
        label.translatesAutoresizingMaskIntoConstraints = false
        controller.view.addSubview(label)
        NSLayoutConstraint.activate([
            reset.topAnchor.constraint(equalTo: controller.view.safeAreaLayoutGuide.topAnchor),
            reset.trailingAnchor.constraint(equalTo: controller.view.trailingAnchor, constant: -8),
            label.topAnchor.constraint(equalTo: reset.bottomAnchor),
            label.leadingAnchor.constraint(equalTo: controller.view.leadingAnchor),
            label.trailingAnchor.constraint(equalTo: controller.view.trailingAnchor),
            label.heightAnchor.constraint(equalToConstant: 28)
        ])
        displayLink = CADisplayLink(target: self, selector: #selector(sample))
        displayLink?.add(to: .main, forMode: .common)
    }

    private func reset() {
        previous = nil
        samples = 0
        maxError = 0
        maxHeightChange = 0
        minTopInset = .greatestFiniteMagnitude
        maxTopInset = 0
        activelyDecelerating = false
        pendingDecelerationCatch = false
        touchesDuringDeceleration = 0
        decelerationCatchDrags = 0
        decelerationTouchOffsetX = 0
        let media = firstMediaView()
        let carousel = currentCarousel(in: media)
        carousel?.setContentOffset(.zero, animated: false)
        publish(media: media, carousel: carousel)
    }

    @objc private func sample() {
        let media = firstMediaView()
        let carousel = currentCarousel(in: media)
        if let carousel {
            if pendingDecelerationCatch && carousel.isDragging && carousel.panGestureRecognizer.state == .changed {
                decelerationCatchDrags += 1
                pendingDecelerationCatch = false
            }
            if carousel.isDecelerating && !carousel.isTracking {
                activelyDecelerating = true
            } else if !carousel.isDecelerating && !carousel.isDragging && !carousel.isTracking {
                activelyDecelerating = false
                pendingDecelerationCatch = false
            }
        }
        defer { publish(media: media, carousel: carousel) }
        guard list.isDragging else { previous = nil; return }
        minTopInset = min(minTopInset, list.safeAreaInsets.top)
        maxTopInset = max(maxTopInset, list.safeAreaInsets.top)
        let offset = list.contentOffset.y
        let recognizer = list.panGestureRecognizer
        // isDragging can remain true during deceleration after the pan ends.
        let pan = recognizer.state == .changed ? recognizer.translation(in: controller.view).y : nil
        let frames = Dictionary(uniqueKeysWithValues: list.visibleCells.compactMap { cell -> (String, CGRect)? in
            guard let path = list.indexPath(for: cell), let id = list.readingItemID?(path) else { return nil }
            return (id, cell.frame)
        })
        defer { previous = (offset, pan, frames) }
        // Avoid elastic edges and the translation reset at the start of each gesture.
        guard let old = previous, offset > 30, old.offset > 30 else { return }
        for (id, frame) in frames {
            guard let oldFrame = old.frames[id] else { continue }
            if let pan, let oldPan = old.pan {
                let movement = frame.minY - offset - (oldFrame.minY - old.offset)
                maxError = max(maxError, abs(movement - (pan - oldPan)))
                samples += 1
            }
            maxHeightChange = max(maxHeightChange, abs(frame.height - oldFrame.height))
        }
    }

    private func firstMediaView() -> StatusMediaUIView? {
        for indexPath in list.indexPathsForVisibleItems.sorted() {
            guard let cell = list.cellForItem(at: indexPath),
                  let media = firstSubview(ofType: StatusMediaUIView.self, in: cell) else { continue }
            return media
        }
        return nil
    }

    private func currentCarousel(in media: StatusMediaUIView?) -> UICollectionView? {
        guard usesCarousel else { return nil }
        guard let carousel = media?.subviews.compactMap({ $0 as? UICollectionView }).first(where: { !$0.isHidden }) else {
            return nil
        }
        if observedCarousel !== carousel {
            observedCarousel?.accessibilityIdentifier = nil
            activelyDecelerating = false
            pendingDecelerationCatch = false
            observedCarousel = carousel
            carousel.accessibilityIdentifier = "timeline-media-carousel"
            if !(carousel.gestureRecognizers ?? []).contains(where: { $0 is CarouselTouchProbe }) {
                carousel.addGestureRecognizer(CarouselTouchProbe { [weak self, weak carousel] in
                    guard let self, let carousel, self.observedCarousel === carousel else { return }
                    if carousel.isDecelerating || self.activelyDecelerating {
                        self.touchesDuringDeceleration += 1
                        self.decelerationTouchOffsetX = carousel.contentOffset.x
                        self.pendingDecelerationCatch = true
                    } else {
                        self.pendingDecelerationCatch = false
                    }
                })
            }
        }
        return carousel
    }

    private func publish(media: StatusMediaUIView?, carousel: UICollectionView?) {
        let values: [String: CGFloat] = [
            "samples": CGFloat(samples), "maxError": maxError, "maxHeightChange": maxHeightChange,
            "topInsetRange": max(maxTopInset - minTopInset, 0), "mediaHeight": media?.bounds.height ?? 0,
            "timelineOffsetY": list.contentOffset.y,
            "carouselOffsetX": carousel?.contentOffset.x ?? 0,
            "carouselDecelerating": carousel?.isDecelerating == true ? 1 : 0,
            "touchesDuringDeceleration": CGFloat(touchesDuringDeceleration),
            "decelerationCatchDrags": CGFloat(decelerationCatchDrags),
            "decelerationTouchOffsetX": decelerationTouchOffsetX,
        ]
        let data = try! JSONSerialization.data(withJSONObject: values, options: .sortedKeys)
        label.text = String(decoding: data, as: UTF8.self)
    }

    private func firstSubview<T: UIView>(ofType _: T.Type, in view: UIView) -> T? {
        if let match = view as? T { return match }
        for subview in view.subviews {
            if let match = firstSubview(ofType: T.self, in: subview) { return match }
        }
        return nil
    }
}
#endif
