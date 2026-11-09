#if os(iOS)
import UIKit

func updatePluginListRefreshControl(_ list: UIScrollView, isRefreshing: Bool) {
    guard list.window != nil, let control = list.refreshControl else { return }
    if isRefreshing {
        guard !control.isRefreshing else { return }
        let top = -list.adjustedContentInset.top
        let reveal = !list.isDragging && list.contentOffset.y <= top + 1
        control.beginRefreshing()
        if reveal {
            list.setContentOffset(
                CGPoint(x: list.contentOffset.x, y: top - max(control.bounds.height, 60)),
                animated: !UIAccessibility.isReduceMotionEnabled
            )
        }
    } else if control.isRefreshing {
        control.endRefreshing()
    }
}
#endif
