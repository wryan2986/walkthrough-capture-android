import AVFoundation
import Foundation
import SwiftUI
import UIKit

enum CaptureMode: String, Codable, CaseIterable {
    case screenshots, trajectory
    var title: String { self == .screenshots ? "Conversation screenshots" : "Trajectory video" }
    var format: String { self == .screenshots ? "ZIP" : "MP4" }
    var symbol: String { self == .screenshots ? "rectangle.stack" : "video" }
}

struct CaptureConfiguration: Codable, Equatable {
    var mode = CaptureMode.screenshots
    var startTime = Calendar.current.startOfDay(for: Date())
    var endTime = Date()
    var selectedTrajectories: [String] = []
    var holdSeconds = 2.0
    var overlapPercent = 65.0

    init() {}
    init(from decoder:Decoder) throws {
        let data=try decoder.container(keyedBy:CodingKeys.self)
        mode=try data.decodeIfPresent(CaptureMode.self,forKey:.mode) ?? mode
        startTime=try data.decodeIfPresent(Date.self,forKey:.startTime) ?? startTime
        endTime=try data.decodeIfPresent(Date.self,forKey:.endTime) ?? endTime
        selectedTrajectories=try data.decodeIfPresent([String].self,forKey:.selectedTrajectories) ?? []
        holdSeconds=min(10,max(0.25,try data.decodeIfPresent(Double.self,forKey:.holdSeconds) ?? holdSeconds))
        overlapPercent=min(85,max(40,try data.decodeIfPresent(Double.self,forKey:.overlapPercent) ?? overlapPercent))
    }
}

struct TrajectoryChoice: Identifiable, Codable, Equatable {
    let id: String
    let title: String
    let timestamp: Date
}

extension JSONDecoder {
    static var captureDecoder: JSONDecoder {
        let decoder=JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let box=try decoder.singleValueContainer()
            let text=try box.decode(String.self)
            let parser=ISO8601DateFormatter()
            parser.formatOptions=[.withInternetDateTime,.withFractionalSeconds]
            if let date=parser.date(from:text) {return date}
            parser.formatOptions=[.withInternetDateTime]
            if let date=parser.date(from:text) {return date}
            throw DecodingError.dataCorruptedError(in:box,debugDescription:"Expected an ISO 8601 timestamp.")
        }
        return decoder
    }
}

struct SavedCapture: Codable, Identifiable, Equatable {
    let id: UUID
    let title: String
    let created: Date
    let mode: CaptureMode
    let screenshotCount: Int?
    let duration: Double?
    let bytes: Int64
    let filename: String
    let inboxIdentity: String?

    var detail: String {
        if let screenshotCount {return "\(screenshotCount) screenshot\(screenshotCount == 1 ? "" : "s")"}
        let seconds=Int((duration ?? 0).rounded())
        return String(format:"%d:%02d",seconds/60,seconds%60)
    }
}

private actor LocalCaptureFiles {
    let library: URL
    init(library:URL) {self.library=library}

    func importFile(_ source:URL, inboxIdentity:String?) async throws -> SavedCapture {
        let scoped=source.startAccessingSecurityScopedResource()
        defer {if scoped {source.stopAccessingSecurityScopedResource()}}
        let ext=source.pathExtension.lowercased()
        guard ext=="mp4" || ext=="zip" else {throw CaptureFileError.format}
        let fm=FileManager.default, id=UUID()
        let stage=library.appendingPathComponent("."+id.uuidString,isDirectory:true)
        try fm.createDirectory(at:stage,withIntermediateDirectories:true)
        defer {try? fm.removeItem(at:stage)}
        let filename=source.lastPathComponent
        let file=stage.appendingPathComponent(filename)
        // A file-provider URL can be a placeholder. Coordination materializes a local readable version.
        var readError:NSError?
        var copyError:Error?
        NSFileCoordinator().coordinate(readingItemAt:source,options:[],error:&readError) { readable in
            do {try fm.copyItem(at:readable,to:file)} catch {copyError=error}
        }
        if let readError {throw readError}
        if let copyError {throw copyError}
        let image: UIImage
        var duration:Double?, count:Int?
        if ext=="zip" {
            let archive=try ScreenshotArchive(url:file)
            count=archive.entries.count
            image=UIImage(cgImage:try archive.image(at:0,longestEdge:360))
        } else {
            let asset=AVURLAsset(url:file)
            guard !(try await asset.loadTracks(withMediaType:.video)).isEmpty, try await asset.load(.isPlayable) else {
                throw CaptureFileError.invalidVideo
            }
            guard try await asset.loadTracks(withMediaType:.audio).isEmpty else {throw CaptureFileError.hasAudio}
            let seconds=try await asset.load(.duration).seconds
            guard seconds.isFinite,seconds>0 else {throw CaptureFileError.invalidVideo}
            duration=seconds
            let gen=AVAssetImageGenerator(asset:asset)
            gen.appliesPreferredTrackTransform=true
            gen.maximumSize=CGSize(width:360,height:360)
            let frame=try await gen.image(at:CMTime(seconds:min(0.5,seconds/3),preferredTimescale:600))
            image=UIImage(cgImage:frame.image)
        }
        guard let jpg=image.jpegData(compressionQuality:0.85) else {throw CaptureFileError.format}
        try jpg.write(to:stage.appendingPathComponent("cover.jpg"),options:.atomic)
        let bytes=try file.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0
        let item=SavedCapture(id:id,title:source.deletingPathExtension().lastPathComponent,
            created:Date(),mode:ext=="zip" ? .screenshots : .trajectory,screenshotCount:count,
            duration:duration,bytes:Int64(bytes),filename:filename,inboxIdentity:inboxIdentity)
        try JSONEncoder().encode(item).write(to:stage.appendingPathComponent("info.json"),options:.atomic)
        try fm.moveItem(at:stage,to:library.appendingPathComponent(id.uuidString,isDirectory:true))
        return item
    }
}

@MainActor final class CaptureStore: ObservableObject {
    @Published var configuration: CaptureConfiguration
    @Published private(set) var trajectories:[TrajectoryChoice]=[]
    @Published private(set) var captures:[SavedCapture]=[]
    @Published private(set) var importing=false
    @Published var presented: SavedCapture?
    private(set) var savedPresentation: UUID?
    @Published var error: String?
    @Published var savedMessage: String?

    let folder:URL, inbox:URL, library:URL
    private let files:LocalCaptureFiles
    private var catalogSignature:Data?
    private var failedInbox=[URL:Date]()
    private var waitingImports=[URL]()
    private let fm=FileManager.default

    init() {
        let docs=FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0]
        folder=docs.appendingPathComponent("WalkthroughCapture",isDirectory:true)
        inbox=folder.appendingPathComponent("Inbox",isDirectory:true)
        let support=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0]
        library=support.appendingPathComponent("Captures",isDirectory:true)
        files=LocalCaptureFiles(library:library)
        let configURL=folder.appendingPathComponent("config.json")
        configuration=CaptureConfiguration()
        do {
            try fm.createDirectory(at:inbox,withIntermediateDirectories:true)
            try fm.createDirectory(at:library,withIntermediateDirectories:true)
            if fm.fileExists(atPath:configURL.path) {
                configuration=try JSONDecoder.captureDecoder.decode(CaptureConfiguration.self,from:Data(contentsOf:configURL))
            } else {try writeConfiguration()}
        } catch {self.error=error.localizedDescription}
        readLibrary()
        refreshCatalog()
    }
    func mediaURL(_ capture:SavedCapture)->URL {itemFolder(capture).appendingPathComponent(capture.filename)}
    func coverURL(_ capture:SavedCapture)->URL {itemFolder(capture).appendingPathComponent("cover.jpg")}
    private func itemFolder(_ capture:SavedCapture)->URL {library.appendingPathComponent(capture.id.uuidString,isDirectory:true)}
    var choicesInRange:[TrajectoryChoice] {
        trajectories.filter{$0.timestamp>=configuration.startTime && $0.timestamp<=configuration.endTime}
    }
    private func readLibrary() {
        let dirs=(try? fm.contentsOfDirectory(at:library,includingPropertiesForKeys:nil,options:.skipsHiddenFiles)) ?? []
        captures=dirs.compactMap { dir in
            guard let uuid=UUID(uuidString:dir.lastPathComponent),
                  let raw=try? Data(contentsOf:dir.appendingPathComponent("info.json")),
                  let item=try? JSONDecoder().decode(SavedCapture.self,from:raw),item.id==uuid else{return nil}
            return item
        }.sorted{$0.created>$1.created}
    }
    func refreshCatalog() {
        let url=folder.appendingPathComponent("trajectories.json")
        guard fm.fileExists(atPath:url.path) else {
            catalogSignature=nil; if !trajectories.isEmpty {trajectories=[]}; return
        }
        do {
            let raw=try Data(contentsOf:url)
            guard raw != catalogSignature else {return}
            catalogSignature=raw
            let decoded=try JSONDecoder.captureDecoder.decode([TrajectoryChoice].self,from:raw)
            var seen=Set<String>()
            trajectories=decoded.filter{!$0.id.isEmpty && seen.insert($0.id).inserted}
                .sorted{$0.timestamp == $1.timestamp ? $0.id < $1.id : $0.timestamp < $1.timestamp}
        } catch {trajectories=[]; self.error="The trajectory list could not be read. \(error.localizedDescription)"}
    }
    private func writeConfiguration(_ value:CaptureConfiguration?=nil) throws {
        let encoder=JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting=[.prettyPrinted,.sortedKeys]
        try encoder.encode(value ?? configuration).write(to:folder.appendingPathComponent("config.json"),options:.atomic)
    }
    func saveConfiguration() {
        if configuration.mode == .trajectory && configuration.startTime > configuration.endTime {
            error="Choose an end time after the start time."; return
        }
        var value=configuration
        if configuration.mode == .trajectory && catalogSignature != nil {
            let selected=Set(configuration.selectedTrajectories)
            value.selectedTrajectories=choicesInRange.filter{selected.contains($0.id)}.map(\.id)
        }
        do {try writeConfiguration(value); savedMessage="Configuration saved for the Mac runner."}
        catch {self.error=error.localizedDescription}
    }
    func importFiles(_ urls:[URL], fromInbox:Bool=false) async {
        guard !urls.isEmpty else {return}
        if importing {
            if !fromInbox {waitingImports.append(contentsOf:urls)}
            return
        }
        importing=true
        defer {importing=false}
        var last:SavedCapture?
        var problems=[String]()
        var batch=urls, inboxBatch=fromInbox
        while !batch.isEmpty {
            for url in batch {
                do {
                    let identity=inboxBatch ? inboxIdentity(of:url) : nil
                    if let identity, let previous=captures.first(where:{$0.inboxIdentity==identity}) {
                        last=previous
                    } else {
                        let item=try await files.importFile(url,inboxIdentity:identity)
                        captures.insert(item,at:0); last=item
                    }
                    if inboxBatch {
                        do {try fm.removeItem(at:url)}
                        catch {failedInbox[url]=modification(url)}
                    }
                } catch {
                    if inboxBatch {failedInbox[url]=modification(url)}
                    problems.append("\(url.lastPathComponent): \(error.localizedDescription)")
                }
            }
            batch=waitingImports; waitingImports=[]; inboxBatch=false
        }
        if let last {savedPresentation=last.id; presented=last}
        if !problems.isEmpty {error=problems.joined(separator:"\n")}
    }
    func open(_ item:SavedCapture) { savedPresentation=nil; presented=item }
    // If iOS terminates after the library move but before inbox cleanup, resume that same import.
    private func inboxIdentity(of url:URL)->String? {
        guard let attributes=try? fm.attributesOfItem(atPath:url.path),
              let number=attributes[.systemFileNumber] as? NSNumber,let size=attributes[.size] as? NSNumber,
              let date=attributes[.modificationDate] as? Date else {return nil}
        return "\(number):\(size):\(date.timeIntervalSince1970.bitPattern):\(url.lastPathComponent)"
    }
    private func modification(_ url:URL)->Date {
        (try? url.resourceValues(forKeys:[.contentModificationDateKey]).contentModificationDate) ?? .distantPast
    }
    private struct TransferReceipt: Decodable {
        let name: String
        let bytes: Int64
    }

    // On a paired phone the host transfers bytes first and writes the receipt last.
    // Moving the verified file here keeps an interrupted copy out of the library.
    private func finishTransfers() {
        let receipts = (try? fm.contentsOfDirectory(at: inbox, includingPropertiesForKeys: nil)) ?? []
        for url in receipts where url.pathExtension == "ready" {
            let key = url.deletingPathExtension().lastPathComponent
            guard key.count == 32, key.allSatisfy({ $0.isHexDigit }),
                  let data = try? Data(contentsOf: url),
                  let receipt = try? JSONDecoder().decode(TransferReceipt.self, from: data),
                  receipt.bytes > 0, !receipt.name.hasPrefix("."),
                  receipt.name == (receipt.name as NSString).lastPathComponent,
                  ["mp4", "zip"].contains((receipt.name as NSString).pathExtension.lowercased()) else { continue }
            let staged = inbox.appendingPathComponent("." + key + ".part")
            let final = inbox.appendingPathComponent(receipt.name)
            if fm.fileExists(atPath: staged.path) {
                guard let size = try? staged.resourceValues(forKeys: [.fileSizeKey]).fileSize,
                      Int64(size) == receipt.bytes else { continue }
                do { try fm.moveItem(at: staged, to: final) }
                catch { continue }
            }
            try? fm.removeItem(at: url)
        }
    }

    func poll() async {
        finishTransfers()
        refreshCatalog()
        guard !importing else{return}
        let urls=(try? fm.contentsOfDirectory(at:inbox,includingPropertiesForKeys:[.contentModificationDateKey],options:.skipsHiddenFiles)) ?? []
        let ready=urls.filter{["zip","mp4"].contains($0.pathExtension.lowercased()) && failedInbox[$0] != modification($0)}
            .sorted{$0.lastPathComponent<$1.lastPathComponent}
        await importFiles(ready,fromInbox:true)
    }
}
