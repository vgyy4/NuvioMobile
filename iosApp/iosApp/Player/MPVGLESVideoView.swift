import Foundation
import UIKit
import OpenGLES
import QuartzCore
import Libmpv

/// OpenGL ES video surface for the iOS 12 build.
///
/// The Vulkan renderer (MoltenVK) needs iOS 15 and a GPU the A7 does not have, so this build draws
/// with mpv's OpenGL ES renderer through the libmpv render API: mpv renders into the framebuffer of
/// a `CAEAGLLayer`, and VideoToolbox frames are mapped into GL textures by mpv's `ios-gl` interop.
///
/// Threading (see mpv's render.h): every `mpv_render_*` call and every GL call runs on `renderQueue`,
/// never from the update callback itself.
final class MPVGLESVideoView: UIView {
    override class var layerClass: AnyClass { CAEAGLLayer.self }

    private let renderQueue = DispatchQueue(label: "app.nuvio.mpv.gles", qos: .userInteractive)
    private var glContext: EAGLContext?
    private var framebuffer: GLuint = 0
    private var colorRenderbuffer: GLuint = 0
    private var renderWidth: GLint = 0
    private var renderHeight: GLint = 0
    private var renderContext: OpaquePointer?

    private let stateLock = NSLock()
    private var needsStorageUpdate = true
    private var renderingEnabled = true
    private var redrawScheduled = false
    private var forceNextRender = false
    private var observers: [NSObjectProtocol] = []

    override init(frame: CGRect) {
        super.init(frame: frame)
        commonInit()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        commonInit()
    }

    private func commonInit() {
        backgroundColor = .black
        isOpaque = true
        contentScaleFactor = UIScreen.main.nativeScale
        if let layer = layer as? CAEAGLLayer {
            layer.isOpaque = true
            layer.drawableProperties = [
                kEAGLDrawablePropertyRetainedBacking: false,
                kEAGLDrawablePropertyColorFormat: kEAGLColorFormatRGBA8,
            ]
        }
        // iOS kills an app that issues GL commands while it is not active.
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: nil) { [weak self] _ in
            self?.suspendRendering()
        })
        observers.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: nil) { [weak self] _ in
            self?.resumeRendering()
        })
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        stateLock.lock()
        needsStorageUpdate = true
        stateLock.unlock()
        requestRender(force: true)
    }

    // MARK: - mpv attachment

    /// Creates the mpv render context. Must be called after `mpv_initialize` and before the first
    /// file is loaded: with `vo=libmpv` the video output needs the context to exist.
    @discardableResult
    func attach(to mpv: OpaquePointer) -> Bool {
        var attached = false
        renderQueue.sync {
            guard renderContext == nil else { attached = true; return }
            guard let context = EAGLContext(api: .openGLES3) ?? EAGLContext(api: .openGLES2),
                  EAGLContext.setCurrent(context) else {
                print("[MPV] Could not create an OpenGL ES context")
                return
            }
            glContext = context
            createFramebuffer()

            var created: OpaquePointer?
            let status: Int32 = "opengl".withCString { api in
                var initParams = mpv_opengl_init_params(
                    get_proc_address: { _, name in MPVGLESVideoView.glProcAddress(name) },
                    get_proc_address_ctx: nil
                )
                return withUnsafeMutablePointer(to: &initParams) { initPointer in
                    var params = [
                        mpv_render_param(type: MPV_RENDER_PARAM_API_TYPE, data: UnsafeMutableRawPointer(mutating: api)),
                        mpv_render_param(type: MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, data: UnsafeMutableRawPointer(initPointer)),
                        mpv_render_param(type: MPV_RENDER_PARAM_INVALID, data: nil),
                    ]
                    return mpv_render_context_create(&created, mpv, &params)
                }
            }
            guard status >= 0, let created else {
                print("[MPV] mpv_render_context_create failed: \(String(cString: mpv_error_string(status)))")
                deleteFramebuffer()
                EAGLContext.setCurrent(nil)
                glContext = nil
                return
            }
            renderContext = created
            mpv_render_context_set_update_callback(created, { raw in
                guard let raw else { return }
                Unmanaged<MPVGLESVideoView>.fromOpaque(raw).takeUnretainedValue().requestRender(force: false)
            }, Unmanaged.passUnretained(self).toOpaque())
            EAGLContext.setCurrent(nil)
            attached = true
        }
        if attached { requestRender(force: true) }
        return attached
    }

    /// Frees the render context. Must run before `mpv_terminate_destroy`.
    func shutdown() {
        stateLock.lock()
        renderingEnabled = false
        stateLock.unlock()
        renderQueue.sync {
            if let context = glContext { EAGLContext.setCurrent(context) }
            if let ctx = renderContext {
                mpv_render_context_set_update_callback(ctx, nil, nil)
                mpv_render_context_free(ctx)
                renderContext = nil
            }
            deleteFramebuffer()
            EAGLContext.setCurrent(nil)
            glContext = nil
        }
    }

    // MARK: - Rendering

    fileprivate func requestRender(force: Bool) {
        stateLock.lock()
        if force { forceNextRender = true }
        if redrawScheduled {
            stateLock.unlock()
            return
        }
        redrawScheduled = true
        stateLock.unlock()
        renderQueue.async { [weak self] in self?.renderPass() }
    }

    private func renderPass() {
        stateLock.lock()
        redrawScheduled = false
        let force = forceNextRender
        forceNextRender = false
        let enabled = renderingEnabled
        stateLock.unlock()

        guard let ctx = renderContext, let glContext else { return }
        // Always consume the update flags so mpv keeps producing frames, even when not drawing.
        let flags = mpv_render_context_update(ctx)
        let frameReady = flags & UInt64(MPV_RENDER_UPDATE_FRAME.rawValue) != 0
        guard enabled, frameReady || force else { return }
        guard EAGLContext.setCurrent(glContext) else { return }

        updateStorageIfNeeded()
        guard renderWidth > 1, renderHeight > 1 else { return }

        glBindFramebuffer(GLenum(GL_FRAMEBUFFER), framebuffer)
        var target = mpv_opengl_fbo(fbo: Int32(framebuffer), w: renderWidth, h: renderHeight, internal_format: 0)
        var flipY: Int32 = 1
        withUnsafeMutablePointer(to: &target) { targetPointer in
            withUnsafeMutablePointer(to: &flipY) { flipPointer in
                var params = [
                    mpv_render_param(type: MPV_RENDER_PARAM_OPENGL_FBO, data: UnsafeMutableRawPointer(targetPointer)),
                    mpv_render_param(type: MPV_RENDER_PARAM_FLIP_Y, data: UnsafeMutableRawPointer(flipPointer)),
                    mpv_render_param(type: MPV_RENDER_PARAM_INVALID, data: nil),
                ]
                _ = mpv_render_context_render(ctx, &params)
            }
        }
        glBindRenderbuffer(GLenum(GL_RENDERBUFFER), colorRenderbuffer)
        glContext.presentRenderbuffer(Int(GL_RENDERBUFFER))
        mpv_render_context_report_swap(ctx)
    }

    private func suspendRendering() {
        stateLock.lock()
        renderingEnabled = false
        stateLock.unlock()
        // Finish queued GL work before the app resigns; later passes skip drawing.
        renderQueue.sync {
            guard let glContext, EAGLContext.setCurrent(glContext) else { return }
            glFinish()
            EAGLContext.setCurrent(nil)
        }
    }

    private func resumeRendering() {
        stateLock.lock()
        renderingEnabled = true
        needsStorageUpdate = true
        stateLock.unlock()
        requestRender(force: true)
    }

    // MARK: - GL objects (render queue only)

    private func createFramebuffer() {
        glGenFramebuffers(1, &framebuffer)
        glGenRenderbuffers(1, &colorRenderbuffer)
        glBindFramebuffer(GLenum(GL_FRAMEBUFFER), framebuffer)
        glBindRenderbuffer(GLenum(GL_RENDERBUFFER), colorRenderbuffer)
        glFramebufferRenderbuffer(
            GLenum(GL_FRAMEBUFFER), GLenum(GL_COLOR_ATTACHMENT0), GLenum(GL_RENDERBUFFER), colorRenderbuffer
        )
        renderWidth = 0
        renderHeight = 0
        stateLock.lock()
        needsStorageUpdate = true
        stateLock.unlock()
    }

    private func deleteFramebuffer() {
        if framebuffer != 0 { glDeleteFramebuffers(1, &framebuffer) }
        if colorRenderbuffer != 0 { glDeleteRenderbuffers(1, &colorRenderbuffer) }
        framebuffer = 0
        colorRenderbuffer = 0
        renderWidth = 0
        renderHeight = 0
    }

    private func updateStorageIfNeeded() {
        stateLock.lock()
        let needsUpdate = needsStorageUpdate
        needsStorageUpdate = false
        stateLock.unlock()
        guard needsUpdate || renderWidth == 0, let glContext else { return }

        glBindFramebuffer(GLenum(GL_FRAMEBUFFER), framebuffer)
        glBindRenderbuffer(GLenum(GL_RENDERBUFFER), colorRenderbuffer)
        if !glContext.renderbufferStorage(Int(GL_RENDERBUFFER), from: eaglLayerForStorage) {
            print("[MPV] renderbufferStorage failed")
        }
        glGetRenderbufferParameteriv(GLenum(GL_RENDERBUFFER), GLenum(GL_RENDERBUFFER_WIDTH), &renderWidth)
        glGetRenderbufferParameteriv(GLenum(GL_RENDERBUFFER), GLenum(GL_RENDERBUFFER_HEIGHT), &renderHeight)
        glFramebufferRenderbuffer(
            GLenum(GL_FRAMEBUFFER), GLenum(GL_COLOR_ATTACHMENT0), GLenum(GL_RENDERBUFFER), colorRenderbuffer
        )
        if glCheckFramebufferStatus(GLenum(GL_FRAMEBUFFER)) != GLenum(GL_FRAMEBUFFER_COMPLETE) {
            print("[MPV] Video framebuffer is incomplete (\(renderWidth)x\(renderHeight))")
        }
    }

    private var eaglLayerForStorage: CAEAGLLayer? { layer as? CAEAGLLayer }

    // MARK: - Symbol lookup

    private static let openGLESBundle = CFBundleGetBundleWithIdentifier("com.apple.opengles" as CFString)

    private static func glProcAddress(_ name: UnsafePointer<CChar>?) -> UnsafeMutableRawPointer? {
        guard let name else { return nil }
        if let bundle = openGLESBundle,
           let symbol = CFBundleGetFunctionPointerForName(bundle, String(cString: name) as CFString) {
            return symbol
        }
        // RTLD_DEFAULT
        return dlsym(UnsafeMutableRawPointer(bitPattern: -2), name)
    }
}
