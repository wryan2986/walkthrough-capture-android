// Run with the macOS Swift toolchain. Input times are seconds on the XCTest movie timeline.
import AVFoundation
import CoreVideo
import Foundation

struct ClipError: LocalizedError {
    let message:String
    init(_ message:String) {self.message=message}
    var errorDescription:String? {message}
}
func exportSegment(_ input:URL, _ destination:URL, start:Double, end:Double) async throws -> [String:Any] {
    let asset=AVURLAsset(url:input)
    let sourceDuration=try await asset.load(.duration).seconds
    guard sourceDuration.isFinite, start.isFinite, end.isFinite, start >= -0.01,
          end > max(0,start), end <= sourceDuration+0.05 else {
        throw ClipError("The recording does not cover both trajectory markers (\(start), \(end), movie \(sourceDuration)).")
    }
    guard let track=try await asset.loadTracks(withMediaType:.video).first else {
        throw ClipError("The XCTest recording contains no video track.")
    }
    let natural=try await track.load(.naturalSize), transform=try await track.load(.preferredTransform)
    let width=Int(natural.width.rounded()), height=Int(natural.height.rounded())
    guard width>0,height>0 else {throw ClipError("The recording has invalid pixel dimensions.")}
    let timescale:Int32=60_000
    let from=CMTime(seconds:max(0,start),preferredTimescale:timescale)
    let through=CMTime(seconds:min(sourceDuration,end),preferredTimescale:timescale)
    let duration=CMTimeSubtract(through,from)
    let reader=try AVAssetReader(asset:asset)
    let yuv=kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
    let output=AVAssetReaderTrackOutput(track:track,outputSettings:[kCVPixelBufferPixelFormatTypeKey as String:yuv])
    output.alwaysCopiesSampleData=false
    guard reader.canAdd(output) else {throw ClipError("Cannot decode the XCTest recording.")}
    reader.add(output)
    let writer=try AVAssetWriter(outputURL:destination,fileType:.mp4)
    writer.movieTimeScale=timescale
    writer.shouldOptimizeForNetworkUse=true
    let settings:[String:Any]=[AVVideoCodecKey:AVVideoCodecType.h264, AVVideoWidthKey:width, AVVideoHeightKey:height,
        AVVideoCompressionPropertiesKey:[AVVideoAverageBitRateKey:min(24_000_000,max(4_000_000,width*height*5)),
                                       AVVideoAllowFrameReorderingKey:false]]
    let writerInput=AVAssetWriterInput(mediaType:.video,outputSettings:settings)
    writerInput.expectsMediaDataInRealTime=false
    writerInput.mediaTimeScale=timescale
    writerInput.transform=transform
    let frames=AVAssetWriterInputPixelBufferAdaptor(assetWriterInput:writerInput,
        sourcePixelBufferAttributes:[kCVPixelBufferPixelFormatTypeKey as String:yuv])
    guard writer.canAdd(writerInput) else {throw ClipError("The host cannot encode the display at its original dimensions.")}
    writer.add(writerInput)
    guard writer.startWriting(), reader.startReading() else {
        throw writer.error ?? reader.error ?? ClipError("The movie pipeline did not start.")
    }
    writer.startSession(atSourceTime:.zero)
    var success=false, frameCount=0
    defer {reader.cancelReading();if !success && writer.status == .writing {writer.cancelWriting()}}
    var previous:CVPixelBuffer?, lastFrame:CVPixelBuffer?, lastOutput=CMTime.invalid
    func append(_ pixel:CVPixelBuffer, at time:CMTime) async throws {
        while !writerInput.isReadyForMoreMediaData {
            guard writer.status == .writing else {throw writer.error ?? ClipError("The movie writer stopped.")}
            try await Task.sleep(nanoseconds:2_000_000)
        }
        guard frames.append(pixel,withPresentationTime:time) else {throw writer.error ?? ClipError("A frame could not be encoded.")}
        lastOutput=time; lastFrame=pixel; frameCount+=1
    }
    while let sample=output.copyNextSampleBuffer() {
        let pts=CMSampleBufferGetPresentationTimeStamp(sample)
        guard pts.isNumeric, let pixel=CMSampleBufferGetImageBuffer(sample) else {continue}
        if pts <= from {previous=pixel;continue}
        if !lastOutput.isValid {
            guard let previous else {throw ClipError("No screen frame covers the start marker.")}
            try await append(previous,at:.zero)
        }
        if pts >= through {break}
        let at=CMTimeConvertScale(CMTimeSubtract(pts,from),timescale:timescale,method:.default)
        if at>lastOutput {try await append(pixel,at:at)}
    }
    if reader.status == .failed {throw reader.error ?? ClipError("Reading the screen recording failed.")}
    if !lastOutput.isValid,let previous {try await append(previous,at:.zero)}
    guard let lastFrame else {throw ClipError("There is no screen frame in the marked interval.")}
    // VFR screen recordings can have seconds of unchanged pixels after their last sample.
    let finalTick=CMTimeSubtract(duration,CMTime(value:1,timescale:timescale))
    if finalTick>lastOutput {try await append(lastFrame,at:finalTick)}
    writer.endSession(atSourceTime:duration); writerInput.markAsFinished()
    await writer.finishWriting()
    guard writer.status == .completed else {throw writer.error ?? ClipError("Silent MP4 finalization failed.")}
    success=true
    let result=AVURLAsset(url:destination)
    let videos=try await result.loadTracks(withMediaType:.video), audio=try await result.loadTracks(withMediaType:.audio)
    let actual=try await result.load(.duration).seconds
    guard videos.count==1, audio.isEmpty, actual.isFinite, abs(actual-duration.seconds)<0.05 else {
        throw ClipError("The resulting MP4 failed its track or duration checks.")
    }
    return ["sourceSeconds":sourceDuration,"startSeconds":from.seconds,"endSeconds":through.seconds,
            "durationSeconds":actual,"width":width,"height":height,"frames":frameCount,"audioTracks":audio.count]
}

do {
    let args=CommandLine.arguments
    guard args.count==5,let start=Double(args[3]),let end=Double(args[4]) else {
        throw ClipError("Usage: ExportSilentVideo.swift INPUT OUTPUT START_SECONDS END_SECONDS")
    }
    let info=try await exportSegment(URL(fileURLWithPath:args[1]),URL(fileURLWithPath:args[2]),start:start,end:end)
    let bytes=try JSONSerialization.data(withJSONObject:info,options:.sortedKeys)
    FileHandle.standardOutput.write(bytes)
} catch {
    FileHandle.standardError.write(Data((error.localizedDescription+"\n").utf8));exit(1)
}
