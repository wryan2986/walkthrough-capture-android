import XCTest
import UIKit
import CryptoKit

/// The target stays a separate application. The runner reads only public accessibility
/// elements and the compositor's on-screen pixels. Exports are local XCTest attachments.
@MainActor
final class AutomaticCaptureTests: XCTestCase {
    private struct Profile: Decodable {
        var chat = "chat.scroll", chatStart = "chat.start", chatEnd = "chat.end"
        var disclosure = "chat.thinking", table = "chat.table"
        var tableStart = "chat.table.start", tableEnd = "chat.table.end"
        var avatar = "profile.open", feed = "activity.list", feedStart = "activity.start", feedEnd = "activity.end"
        var activityPrefix = "activity.", steps = "steps.list", stepsStart = "steps.start", stepsEnd = "steps.end"
        var stepPrefix = "step.", detail = "detail.scroll", detailStart = "detail.start", detailEnd = "detail.end", back = "nav.back"
        mutating func override(with names: [String:String]) {
            let mapping: [String:WritableKeyPath<Profile,String>] = [
                "chat": \.chat, "chatStart": \.chatStart, "chatEnd": \.chatEnd, "disclosure": \.disclosure,
                "table": \.table, "tableStart": \.tableStart, "tableEnd": \.tableEnd,
                "avatar": \.avatar, "feed": \.feed, "feedStart": \.feedStart, "feedEnd": \.feedEnd, "activityPrefix": \.activityPrefix,
                "steps": \.steps, "stepsStart": \.stepsStart, "stepsEnd": \.stepsEnd, "stepPrefix": \.stepPrefix,
                "detail": \.detail, "detailStart": \.detailStart, "detailEnd": \.detailEnd, "back": \.back
            ]
            for (key,value) in names { if let field=mapping[key], !value.isEmpty { self[keyPath:field]=value } }
        }
    }
    private struct Config: Decodable {
        var selectedTrajectories: [String]?
        var startTime: String?
        var endTime: String?
        var holdSeconds: Double?
        var overlapPercent: Double?
        var selectors: [String:String]?
    }
    private struct Row: Codable { let id: String; let title: String; let timestamp: String }
    private struct CaptureError: LocalizedError {
        let reason: String
        init(_ message: String) { reason=message }
        var errorDescription: String? { reason }
    }
    private var app: XCUIApplication!
    private var p=Profile(), cfg=Config(), mode="", stills=0, stepsRead=0, verifiedMoves=0
    private var previousScreen:SHA256.Digest?
    private var consecutiveDuplicates=0
    private var completed: [String]=[], opened=Set<String>(), visitedTables=Set<String>(), tableSlices=Set<String>()
    private var hold: Double { min(10,max(0.25,cfg.holdSeconds ?? 2)) }
    private var stride: CGFloat { CGFloat(1 - min(85,max(40,cfg.overlapPercent ?? 65))/100) }
    private let maxMoves=240

    private func attach(_ name: String, data: Data, type: String) {
        let a=XCTAttachment(data: data, uniformTypeIdentifier: type)
        a.name=name; a.lifetime = .keepAlways; add(a)
    }
    private func json(_ name: String,_ value: Any) {
        if let bytes=try? JSONSerialization.data(withJSONObject:value,options:[.sortedKeys]) {
            attach(name,data:bytes,type:"public.json")
        }
    }
    private func env(_ key: String)->String? {
        let e=ProcessInfo.processInfo.environment
        return e["WC_"+key] ?? e["TEST_RUNNER_WC_"+key]
    }
    private func checkTarget() throws {
        guard app.state == .runningForeground else { throw CaptureError("Target left the foreground.") }
    }
    private func element(_ id: String)->XCUIElement {
        app.descendants(matching:.any).matching(NSPredicate(format:"identifier == %@",id)).firstMatch
    }
    private func scope(_ id:String) throws -> XCUIElement {
        try checkTarget()
        let e=element(id)
        guard e.waitForExistence(timeout:4), !e.frame.isEmpty else { throw CaptureError("Expected view unavailable: "+id) }
        return e
    }
    private func visible(_ e:XCUIElement, inside viewport:XCUIElement)->Bool {
        guard e.exists, !e.frame.isEmpty else { return false }
        let clip=viewport.frame.intersection(app.frame)
        return !clip.isNull && clip.insetBy(dx:-1,dy:-1).contains(CGPoint(x:e.frame.midX,y:e.frame.midY)) && e.isHittable
    }
    private func at(_ id:String, inside viewport:XCUIElement,horizontal:Bool=false)->Bool {
        let e=element(id)
        guard e.exists, !e.frame.isEmpty else {return false}
        let clip=viewport.frame.intersection(app.frame).insetBy(dx:-1,dy:-1)
        guard e.frame.intersects(clip) else{return false}
        return horizontal ? e.frame.minX>=clip.minX && e.frame.maxX<=clip.maxX :
                            e.frame.minY>=clip.minY && e.frame.maxY<=clip.maxY && e.isHittable
    }
    private func tap(_ id:String) throws {
        let e=try scope(id)
        guard e.isHittable else { throw CaptureError("Requested control is off screen: "+id) }
        e.tap(); settle()
    }
    private func settle(){ Thread.sleep(forTimeInterval:0.24) }
    private func swipe(_ v:XCUIElement,horizontal:Bool,forward:Bool) throws {
        try checkTarget()
        guard !v.frame.intersection(app.frame).isEmpty else { throw CaptureError("Scroll view left the display.") }
        let center:CGFloat=0.5, direction:CGFloat=forward ? 1 : -1
        let a=center+direction*stride/2, b=center-direction*stride/2
        // Convert the visible intersection rather than swiping over an occluded end of a table.
        let box=v.frame.intersection(app.frame)
        func point(_ t:CGFloat)->XCUICoordinate {
            let x=box.minX+box.width*(horizontal ? t : center), y=box.minY+box.height*(horizontal ? center : t)
            return app.coordinate(withNormalizedOffset:.zero).withOffset(CGVector(dx:x-app.frame.minX,dy:y-app.frame.minY))
        }
        point(a).press(forDuration:0.08,thenDragTo:point(b),withVelocity:.slow,thenHoldForDuration:0.18)
        settle();try checkTarget()
    }

    /// Compare the visible body rather than volatile clock/status bars.
    private func fingerprint(_ v:XCUIElement) throws -> [UInt8] {
        try checkTarget()
        guard let cg=XCUIScreen.main.screenshot().image.cgImage else { throw CaptureError("The display could not be read.") }
        let rect=v.frame.intersection(app.frame), sx=CGFloat(cg.width)/app.frame.width, sy=CGFloat(cg.height)/app.frame.height
        let crop=CGRect(x:(rect.minX-app.frame.minX)*sx,y:(rect.minY-app.frame.minY)*sy,width:rect.width*sx,height:rect.height*sy)
        guard let section=cg.cropping(to:crop.integral) else { throw CaptureError("No visible scroll area.") }
        var pixels=[UInt8](repeating:0,count:40*64)
        let valid=pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context=CGContext(data:buffer.baseAddress,width:40,height:64,bitsPerComponent:8,bytesPerRow:40,
                                        space:CGColorSpaceCreateDeviceGray(),bitmapInfo:CGImageAlphaInfo.none.rawValue) else { return false }
            context.interpolationQuality = .low
            context.draw(section,in:CGRect(x:0,y:0,width:40,height:64));return true
        }
        guard valid else { throw CaptureError("Could not compare screen positions.") }
        return pixels
    }
    private func changed(_ x:[UInt8],_ y:[UInt8])->Bool {
        let difference=zip(x,y).reduce(0){$0+abs(Int($1.0)-Int($1.1))}
        return Double(difference)/Double(x.count)>0.8
    }
    /// Use only unique accessible content anchors. Fixed controls that never move
    /// cannot demonstrate an overlapping scroll. Labels stay inside this process.
    private func anchors(_ v:XCUIElement, horizontal:Bool) throws -> [String:CGFloat] {
        let visible=v.frame.intersection(app.frame)
        var positions=[String:CGFloat](), duplicate=Set<String>()
        func collect(_ s:XCUIElementSnapshot) {
            let f=s.frame, point=CGPoint(x:f.midX,y:f.midY)
            if visible.contains(point), !f.isEmpty, [.staticText,.button,.cell,.image,.link].contains(s.elementType) {
                let key=s.identifier.isEmpty ? s.label : s.identifier
                if !key.isEmpty {
                    if positions[key] != nil { duplicate.insert(key) }
                    else { positions[key] = horizontal ? point.x : point.y }
                }
            }
            for child in s.children { collect(child) }
        }
        collect(try v.snapshot())
        for key in duplicate { positions.removeValue(forKey:key) }
        return positions
    }
    private func overlap(_ before:[String:CGFloat],_ after:[String:CGFloat],extent:CGFloat,forward:Bool)->Bool {
        let sign:CGFloat=forward ? 1 : -1
        return before.contains { key, old in
            guard let new=after[key] else { return false }
            let moved=(old-new)*sign
            return moved>0.75 && moved<=extent*min(0.68,stride+0.07)
        }
    }
    /// For text blocks with no shared accessibility label, register the pixels
    /// along the gesture axis. Reject flat images and competing alignments.
    private func pixelsOverlap(_ before:[UInt8],_ after:[UInt8],horizontal:Bool,forward:Bool)->Bool {
        let width=40, height=64, axis=horizontal ? width : height
        var scores:[(Int,Double)]=[]
        for shift in 1...max(1,Int(Double(axis)*Double(min(0.68,stride+0.07)))) {
            let dx=horizontal ? (forward ? shift : -shift) : 0
            let dy=horizontal ? 0 : (forward ? shift : -shift)
            var n=0.0,x=0.0,y=0.0,xx=0.0,yy=0.0,xy=0.0
            for row in max(2,2-dy)..<min(height-2,height-2-dy) {
                for col in max(2,2-dx)..<min(width-2,width-2-dx) {
                    let a=Double(before[(row+dy)*width+col+dx]), b=Double(after[row*width+col])
                    n+=1;x+=a;y+=b;xx+=a*a;yy+=b*b;xy+=a*b
                }
            }
            let vx=xx/n-pow(x/n,2), vy=yy/n-pow(y/n,2)
            if vx>36,vy>36 {scores.append((shift,(xy/n-x*y/(n*n))/sqrt(vx*vy)))}
        }
        guard let best=scores.max(by:{$0.1<$1.1}),best.1>0.92 else{return false}
        return scores.filter{abs($0.0-best.0)>2}.allSatisfy{$0.1<best.1-0.035}
    }
    private func walk(_ v:XCUIElement, horizontal:Bool=false, forward:Bool=true,
                      until end:()->Bool, visit:() throws -> Void) throws {
        var stalled=0
        for _ in 0..<maxMoves {
            try visit()
            if end() { return }
            let before=try fingerprint(v)
            let checkOverlap=forward || horizontal
            let positions=checkOverlap ? try anchors(v,horizontal:horizontal) : [:]
            try swipe(v,horizontal:horizontal,forward:forward)
            let after=try fingerprint(v)
            let moved=changed(before,after)
            if moved && checkOverlap {
                let extent=horizontal ? v.frame.intersection(app.frame).width : v.frame.intersection(app.frame).height
                let current=try anchors(v,horizontal:horizontal)
                guard overlap(positions,current,extent:extent,forward:forward) ||
                        pixelsOverlap(before,after,horizontal:horizontal,forward:forward) else {
                    throw CaptureError("Successive screenshot positions had no verifiable overlapping content.")
                }
                verifiedMoves+=1
            }
            stalled = moved ? 0 : stalled+1
            if stalled>=3 && !end() { throw CaptureError("A scroll stopped before its configured boundary was visible.") }
        }
        throw CaptureError("Safety limit reached before the scroll boundary.")
    }
    private func saveScreen() throws {
        try checkTarget()
        let data=XCUIScreen.main.screenshot().pngRepresentation
        try checkTarget()
        let digest=SHA256.hash(data:data)
        if digest==previousScreen {consecutiveDuplicates+=1;return}
        previousScreen=digest;stills+=1
        attach(String(format:"capture-%04d.png",stills), data:data,type:"public.png")
    }
    private func expanded(_ e:XCUIElement)->Bool {
        e.identifier.hasSuffix(".expanded") || (e.value as? String)?.lowercased()=="expanded"
    }
    private func candidateIDs(_ prefix:String,in v:XCUIElement)->[String] {
        let q=v.descendants(matching:.any).matching(NSPredicate(format:"identifier BEGINSWITH %@",prefix))
        var seen=Set<String>()
        return q.allElementsBoundByIndex.compactMap { element in
            let id=element.identifier
            return !id.isEmpty && visible(element,inside:v) && seen.insert(id).inserted ? id:nil
        }
    }
    private func tables(_ prefix:String,in v:XCUIElement)->[String] {
        let q=v.descendants(matching:.any).matching(NSPredicate(format:"identifier BEGINSWITH %@",prefix))
        let frame=v.frame.intersection(app.frame)
        return q.allElementsBoundByIndex.compactMap{ pane in
            let id=pane.identifier, clip=pane.frame.intersection(frame)
            guard (id==prefix || pane.elementType == .scrollView || pane.elementType == .collectionView),
                  !clip.isNull,clip.height>=12,clip.width>=30 else{return nil}
            return id
        }
    }
    private func conversation() throws {
        for _ in 0..<4 {
            if element(p.chat).exists {break}
            guard element(p.feed).exists || element(p.steps).exists || element(p.detail).exists else {break}
            try goBack()
        }
        let scroll=try scope(p.chat)
        try walk(scroll,forward:false,until:{self.at(self.p.chatStart,inside:scroll)},visit:{})
        try walk(scroll,until:{self.at(self.p.chatEnd,inside:scroll)}) {
            try self.saveScreen()
            for _ in 0..<64 {
                guard let id=self.candidateIDs(self.p.disclosure,in:scroll).first(where:{
                    !self.opened.contains($0) && !self.expanded(self.element($0))
                }) else { break }
                let e=self.element(id)
                try self.tap(id)
                guard self.element(id+".expanded").exists || (e.exists && self.expanded(e)) else {
                    throw CaptureError("An expansion could not be verified.")
                }
                self.opened.insert(id);try self.saveScreen()
            }
            if self.candidateIDs(self.p.disclosure,in:scroll).contains(where:{
                !self.opened.contains($0) && !self.expanded(self.element($0))
            }) {throw CaptureError("Expansion limit reached.")}
            for id in self.tables(self.p.table,in:scroll) {
                let table=self.element(id)
                guard id==self.p.table || table.elementType == .scrollView || table.elementType == .collectionView else {continue}
                let clip=table.frame.intersection(scroll.frame).intersection(self.app.frame)
                let slice=id+"@"+String(Int((clip.minY-table.frame.minY).rounded()))+":"+String(Int(clip.height.rounded()))
                if self.tableSlices.contains(slice){continue}
                let start=id==self.p.table ? self.p.tableStart : id+".start", end=id==self.p.table ? self.p.tableEnd : id+".end"
                try self.walk(table,horizontal:true,forward:false,until:{self.at(start,inside:table,horizontal:true)},visit:{})
                try self.walk(table,horizontal:true,until:{self.at(end,inside:table,horizontal:true)},visit:{try self.saveScreen()})
                try self.walk(table,horizontal:true,forward:false,until:{self.at(start,inside:table,horizontal:true)},visit:{})
                self.tableSlices.insert(slice); self.visitedTables.insert(id)
            }
        }
    }
    private func instant(_ s:String)->Date? {
        let parser=ISO8601DateFormatter()
        if let simple=parser.date(from:s){return simple}
        parser.formatOptions=[.withInternetDateTime,.withFractionalSeconds];return parser.date(from:s)
    }
    private func top(_ list:XCUIElement, marker:String, fallback:String?) throws {
        try walk(list,forward:false,until:{
            self.at(marker,inside:list) || (fallback.map{self.at($0,inside:list)} ?? false)
        },visit:{})
    }
    private func feed() throws -> XCUIElement {
        for _ in 0..<3 {
            if element(p.feed).exists || element(p.chat).exists {break}
            guard element(p.steps).exists || element(p.detail).exists else {break}
            try goBack()
        }
        if !element(p.feed).exists { try tap(p.avatar) }
        return try scope(p.feed)
    }
    private func discovery() throws -> [Row] {
        let list=try feed()
        try top(list,marker:p.feedStart,fallback:nil)
        let start=cfg.startTime.flatMap(instant),end=cfg.endTime.flatMap(instant)
        if (cfg.startTime != nil && start==nil) || (cfg.endTime != nil && end==nil) ||
            (start != nil && end != nil && start! > end!) { throw CaptureError("Invalid ISO-8601 time range.") }
        var rows:[Row]=[], known=Set<String>()
        try walk(list,until:{self.at(self.p.feedEnd,inside:list)}) {
            for id in self.candidateIDs(self.p.activityPrefix,in:list)
                where id != self.p.feed && id != self.p.feedEnd && id != self.p.feedStart && known.insert(id).inserted {
                let item=self.element(id)
                guard let raw=item.value as? String,let date=self.instant(raw) else {
                    throw CaptureError("Activity timestamp was unavailable or ambiguous.")
                }
                if let start, date<start {continue};if let end,date>end {continue}
                rows.append(Row(id:String(id.dropFirst(self.p.activityPrefix.count)),title:item.label,timestamp:raw))
            }
        }
        attach("discovery.json",data:try JSONEncoder().encode(rows),type:"public.json")
        return rows
    }
    private func locate(_ id:String,in list:XCUIElement,end:String) throws {
        try walk(list,until:{self.at(id,inside:list) || self.at(end,inside:list)},visit:{})
        guard at(id,inside:list) else { throw CaptureError("A previously selected item is no longer present.") }
    }
    private func goBack() throws {
        if element(p.back).exists {try tap(p.back);return}
        let button=app.navigationBars.buttons.matching(NSPredicate(format:"label ==[c] %@ OR identifier ==[c] %@","Back","Back")).firstMatch
        guard button.exists,button.isHittable else {throw CaptureError("Back control unavailable.")}
        button.tap();settle()
    }
    private func record(rows:[Row]) throws {
        let selection=cfg.selectedTrajectories ?? []
        guard !selection.isEmpty, Set(selection).count==selection.count,
              Set(selection).isSubset(of:Set(rows.map(\.id))) else {throw CaptureError("Select distinct IDs from the discovered time range.")}
        let ordered=rows.filter{selection.contains($0.id)}
        let list=try feed()
        try top(list,marker:p.feedStart,fallback:nil)
        json("video-start.json",["time":Date().timeIntervalSince1970])
        defer {json("video-end.json",["time":Date().timeIntervalSince1970])}
        for row in ordered {
            let id=p.activityPrefix+row.id
            try locate(id,in:list,end:p.feedEnd);Thread.sleep(forTimeInterval:hold);try tap(id)
            let steps=try scope(p.steps)
            try top(steps,marker:p.stepsStart,fallback:nil)
            var ids:[String]=[]
            try walk(steps,until:{self.at(self.p.stepsEnd,inside:steps)}) {
                for id in self.candidateIDs(self.p.stepPrefix,in:steps) where !ids.contains(id){ids.append(id)}
            }
            guard !ids.isEmpty else {throw CaptureError("No identifiable steps were exposed for a selected activity.")}
            try top(steps,marker:p.stepsStart,fallback:nil)
            for step in ids {
                try locate(step,in:steps,end:p.stepsEnd);Thread.sleep(forTimeInterval:hold);try tap(step)
                let body=try scope(p.detail)
                try top(body,marker:p.detailStart,fallback:nil)
                try walk(body,until:{self.at(self.p.detailEnd,inside:body)},visit:{Thread.sleep(forTimeInterval:self.hold)})
                stepsRead+=1;try goBack();_ = try scope(p.steps)
            }
            completed.append(row.id);try goBack();_ = try scope(p.feed)
        }
    }
    func testAutomaticCapture() throws {
        mode=env("MODE") ?? "conversation"
        var error:Error?
        do {
            let raw=env("CONFIG_JSON") ?? "{}"
            cfg=try JSONDecoder().decode(Config.self,from:Data(raw.utf8))
            p.override(with:cfg.selectors ?? [:])
            guard let bundle=env("TARGET_BUNDLE"), !bundle.isEmpty else {throw CaptureError("Missing target bundle ID.")}
            app=XCUIApplication(bundleIdentifier:bundle)
            app.activate();try checkTarget()
            switch mode {
            case "conversation": try conversation()
            case "discover": _ = try discovery()
            case "trajectory": try record(rows:discovery())
            default: throw CaptureError("Unsupported capture mode.")
            }
        } catch let problem {error=problem}
        json("result.json",["mode":mode,"status":error==nil ? "complete":"partial","screenshots":stills,
                            "expanded":opened.count,"tables":visitedTables.count,"verifiedOverlapMoves":verifiedMoves,
                            "consecutiveDuplicatesSkipped":consecutiveDuplicates,"completedTrajectories":completed,
                            "verifiedSteps":stepsRead,"reason":error?.localizedDescription ?? ""])
        if let error {throw error}
    }
}
