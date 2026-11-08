import WidgetKit
import SwiftUI

@main
struct DownloadsWidgetBundle: WidgetBundle {
    @WidgetBundleBuilder
    var body: some Widget {
        // Live Activities only exist on iOS 16.1+. On iOS 15 the bundle is simply empty.
        if #available(iOSApplicationExtension 16.1, *) {
            DownloadsLiveActivityWidget()
        }
    }
}
