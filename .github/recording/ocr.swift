// Prints the text found in a screenshot as JSON: [{"text","x","y","w","h"}] in points
// (pixels / 2, origin top-left). Used by drive.py to tap buttons by what they say.
import Foundation
import Vision
import AppKit

let path = CommandLine.arguments[1]
guard let image = NSImage(contentsOfFile: path),
      let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
    print("[]")
    exit(0)
}
let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.usesLanguageCorrection = false
try VNImageRequestHandler(cgImage: cg).perform([request])
let w = Double(cg.width), h = Double(cg.height)
var out: [[String: Any]] = []
for observation in request.results ?? [] {
    guard let candidate = observation.topCandidates(1).first else { continue }
    let b = observation.boundingBox
    out.append([
        "text": candidate.string,
        "x": b.minX * w / 2,
        "y": (1 - b.maxY) * h / 2,
        "w": b.width * w / 2,
        "h": b.height * h / 2,
    ])
}
let data = try JSONSerialization.data(withJSONObject: out)
print(String(data: data, encoding: .utf8)!)
