#if canImport(AppKit)
import AppKit
/// The platform font type (`NSFont` on macOS, `UIFont` on iOS).
public typealias PlatformFont = NSFont
/// The platform color type (`NSColor` on macOS, `UIColor` on iOS).
public typealias PlatformColor = NSColor
#elseif canImport(UIKit)
import UIKit
public typealias PlatformFont = UIFont
public typealias PlatformColor = UIColor
#endif
