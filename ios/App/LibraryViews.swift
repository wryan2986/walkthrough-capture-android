import AVKit
import SwiftUI
import UIKit

struct CaptureLibrary:View {
    @EnvironmentObject private var store:CaptureStore
    let onImport:()->Void
    var body:some View {
        Group {
            if store.captures.isEmpty {
                ContentUnavailableView {
                    Label("No captures yet",systemImage:"rectangle.stack")
                } description:{
                    Text("Finished captures appear here. You can also import a screenshot ZIP or silent MP4 from Files.")
                } actions:{
                    Button("Import capture",action:onImport).buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("import-capture-empty").disabled(store.importing)
                }
            } else {
                List(store.captures) { capture in
                    Button {store.open(capture)} label:{
                        HStack(spacing:14) {
                            LocalCover(url:store.coverURL(capture))
                                .frame(width:58,height:76)
                                .background(CaptureStyle.accent.opacity(0.08))
                                .clipShape(RoundedRectangle(cornerRadius:9,style:.continuous))
                            VStack(alignment:.leading,spacing:4) {
                                Text(capture.title).font(.headline).foregroundStyle(.primary).lineLimit(2)
                                Text("\(capture.mode.format) · \(capture.detail)").font(.subheadline)
                                    .foregroundStyle(.secondary)
                                Text(capture.created.formatted(date:.abbreviated,time:.shortened))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer(minLength:2)
                            Image(systemName:"chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                        }.padding(.vertical,4).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("open-capture-"+capture.id.uuidString)
                }.listStyle(.insetGrouped).scrollContentBackground(.hidden)
            }
        }
        .background(CaptureStyle.paper)
        .navigationTitle("Library")
        .toolbar {
            ToolbarItem(placement:.topBarTrailing) {
                if store.importing {ProgressView("Importing")}
                else {Button(action:onImport){Label("Import",systemImage:"square.and.arrow.down")}
                    .accessibilityIdentifier("import-capture")}
            }
        }
    }
}

private struct LocalCover:View {
    let url:URL
    @State private var image:UIImage?
    var body:some View {
        Group {
            if let image {Image(uiImage:image).resizable().scaledToFit()}
            else {Image(systemName:"rectangle.stack").foregroundStyle(CaptureStyle.accent)}
        }
        .accessibilityHidden(true)
        .task(id:url) {
            let file=url
            let cover=await Task.detached(priority:.utility){UIImage(contentsOfFile:file.path)}.value
            if !Task.isCancelled {image=cover}
        }
    }
}

struct CaptureDetail:View {
    @EnvironmentObject private var store:CaptureStore
    @Environment(\.dismiss) private var dismiss
    let capture:SavedCapture
    @State private var index=0
    @State private var player:AVPlayer?
    @State private var exporting=false
    @State private var expanded=false
    var body:some View {
        NavigationStack {
            VStack(spacing:12) {
                if !expanded {
                    VStack(alignment:.leading,spacing:4) {
                        Text(capture.title).font(.title3.weight(.semibold)).lineLimit(2)
                        Text("\(capture.mode.format) · \(capture.detail) · \(ByteCountFormatter.string(fromByteCount:capture.bytes,countStyle:.file))")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }.frame(maxWidth:.infinity,alignment:.leading)
                }
                ZStack {
                    RoundedRectangle(cornerRadius:12).fill(Color.black.opacity(0.055))
                    if capture.mode == .trajectory {
                        if let player {VideoPlayer(player:player)}
                        else {ProgressView()}
                    } else {
                        ScreenshotPage(url:store.mediaURL(capture),index:index)
                    }
                }.clipShape(RoundedRectangle(cornerRadius:12))
                if capture.mode == .screenshots {
                    HStack {
                        Button {index-=1} label:{Label("Previous",systemImage:"chevron.left")}
                            .disabled(index==0).accessibilityIdentifier("previous-screenshot")
                        Spacer(minLength:3)
                        Text("\(index+1) of \(capture.screenshotCount ?? 1)")
                            .font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                            .accessibilityLabel("Screenshot \(index+1) of \(capture.screenshotCount ?? 1)")
                            .accessibilityIdentifier("screenshot-position")
                        Spacer(minLength:3)
                        Button {index+=1} label:{Label("Next",systemImage:"chevron.right")}
                            .disabled(index+1 >= (capture.screenshotCount ?? 1)).accessibilityIdentifier("next-screenshot")
                    }.controlSize(.regular).buttonStyle(.bordered)
                }
                if !expanded {
                    Button {exporting=true} label:{
                        Label("Save to Files",systemImage:"folder").fontWeight(.semibold).frame(maxWidth:.infinity)
                    }.buttonStyle(.borderedProminent).controlSize(.large)
                        .accessibilityIdentifier("save-to-files")
                }
            }.padding(.horizontal,expanded ? 4 : 16).padding(.vertical,10)
            .background(CaptureStyle.paper)
            .navigationTitle(store.savedPresentation==capture.id ? "Capture saved" : "Capture preview").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement:.topBarLeading){Button("Done"){dismiss()}}
                ToolbarItem(placement:.topBarTrailing) {
                    Button {expanded.toggle()} label:{
                        Label(expanded ? "Reduce preview" : "Enlarge preview",systemImage:expanded ?
                                "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right")
                    }.accessibilityIdentifier("enlarge-preview")
                }
            }
            .sheet(isPresented:$exporting) {ExportPicker(url:store.mediaURL(capture))}
        }
        .tint(CaptureStyle.accent)
        .presentationDragIndicator(.visible)
        .task(id:capture.id) {
            guard capture.mode == .trajectory else{return}
            let movie=AVPlayer(url:store.mediaURL(capture))
            movie.isMuted=true; movie.allowsExternalPlayback=false; player=movie
        }
        .onDisappear {player?.pause()}
    }
}

private struct ExportPicker:UIViewControllerRepresentable {
    let url:URL
    func makeUIViewController(context:Context)->UIDocumentPickerViewController {
        UIDocumentPickerViewController(forExporting:[url],asCopy:true)
    }
    func updateUIViewController(_ controller:UIDocumentPickerViewController,context:Context) {}
}

private actor PageReader {
    private var archive:ScreenshotArchive?
    private var opened:URL?
    func image(_ url:URL,_ index:Int) throws -> UIImage {
        if opened != url || archive==nil {
            archive=try ScreenshotArchive(url:url); opened=url
        }
        guard let archive else {throw CaptureFileError.invalidArchive}
        return UIImage(cgImage:try archive.image(at:index,longestEdge:3072))
    }
}
private struct ScreenshotPage:View {
    let url:URL
    let index:Int
    @State private var reader=PageReader()
    @State private var image:UIImage?
    @State private var errorMessage:String?
    var body:some View {
        Group {
            if let image {ZoomImage(image:image).accessibilityLabel("Screenshot \(index+1)")}
            else if let errorMessage {ContentUnavailableView("Preview unavailable",systemImage:"exclamationmark.triangle",description:Text(errorMessage))}
            else {ProgressView()}
        }
        .task(id:"\(url.path)-\(index)") {
            image=nil;errorMessage=nil
            do {
                let result=try await reader.image(url,index)
                if !Task.isCancelled {image=result}
            } catch {if !Task.isCancelled {errorMessage=error.localizedDescription}}
        }
    }
}
private struct ZoomImage:UIViewRepresentable {
    let image:UIImage
    func makeUIView(context:Context)->ZoomScroll {ZoomScroll()}
    func updateUIView(_ view:ZoomScroll,context:Context) {view.show(image)}
}
private final class ZoomScroll:UIScrollView,UIScrollViewDelegate {
    let picture=UIImageView()
    private var previousSize=CGSize.zero
    init() {
        super.init(frame:.zero); delegate=self; minimumZoomScale=1;maximumZoomScale=4
        bouncesZoom=true;showsHorizontalScrollIndicator=false;showsVerticalScrollIndicator=false
        picture.contentMode = .scaleAspectFit; addSubview(picture)
        let tap=UITapGestureRecognizer(target:self,action:#selector(toggleZoom(_:)))
        tap.numberOfTapsRequired=2;addGestureRecognizer(tap)
        isAccessibilityElement=true;accessibilityTraits = .image
    }
    required init?(coder:NSCoder){fatalError("Use init()")}
    func show(_ image:UIImage) {
        guard picture.image !== image else{return}
        setZoomScale(1,animated:false);picture.image=image;setNeedsLayout()
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.size != previousSize else{return}
        previousSize=bounds.size
        setZoomScale(1,animated:false)
        picture.frame=CGRect(origin:.zero,size:bounds.size);contentSize=bounds.size
    }
    func viewForZooming(in scrollView:UIScrollView)->UIView? {picture}
    @objc private func toggleZoom(_ sender:UITapGestureRecognizer) {
        if zoomScale>1 {setZoomScale(1,animated:true);return}
        let point=sender.location(in:picture), size=CGSize(width:bounds.width/2,height:bounds.height/2)
        zoom(to:CGRect(x:point.x-size.width/2,y:point.y-size.height/2,width:size.width,height:size.height),animated:true)
    }
}
