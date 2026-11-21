import Foundation
import ImageIO
import zlib

/// Reads the ZIP directory without extracting user-supplied filenames to disk.
struct ScreenshotArchive {
    struct Entry {
        let name: String
        let method: Int
        let offset: Int
        let packed: Int
        let size: Int
        let crc: UInt32
    }
    let data: Data
    let entries: [Entry]

    init(url: URL) throws {
        let bytes = try Data(contentsOf: url, options: .mappedIfSafe)
        data = bytes
        guard bytes.count >= 22 else { throw CaptureFileError.invalidArchive }
        var end = bytes.count - 22
        let limit = max(0, end - 65_535)
        while end >= limit {
            if bytes.word(end, 4) == 0x06054b50,
               let comment = bytes.word(end + 20, 2), end + 22 + comment == bytes.count { break }
            end -= 1
        }
        guard end >= limit, bytes.word(end+4,2)==0, bytes.word(end+6,2)==0,
              var count = bytes.word(end+10,2), var position = bytes.word(end+16,4) else {
            throw CaptureFileError.invalidArchive
        }
        if count == 65_535 || position == Int(UInt32.max) {
            guard bytes.word(end-20,4)==0x07064b50, let wide=bytes.word(end-12,8), wide <= bytes.count-56,
                  bytes.word(wide,4)==0x06064b50, let exactCount=bytes.word(wide+32,8),
                  let exactPosition=bytes.word(wide+48,8) else {throw CaptureFileError.invalidArchive}
            count=exactCount; position=exactPosition
        }
        guard count <= bytes.count/46 else {throw CaptureFileError.invalidArchive}
        var found = [Entry]()
        for _ in 0..<count {
            guard bytes.word(position,4)==0x02014b50, let flags=bytes.word(position+8,2),
                  let method=bytes.word(position+10,2), let crc=bytes.word(position+16,4),
                  var packed=bytes.word(position+20,4), var size=bytes.word(position+24,4),
                  let length=bytes.word(position+28,2), let extra=bytes.word(position+30,2),
                  let comment=bytes.word(position+32,2), var local=bytes.word(position+42,4) else {
                throw CaptureFileError.invalidArchive
            }
            let from=position+46, extrasAt=from+length, after=extrasAt+extra+comment
            guard after <= bytes.count else {throw CaptureFileError.invalidArchive}
            let name=String(decoding:bytes[from..<extrasAt],as:UTF8.self)
            if name.lowercased().hasSuffix(".png") && !name.hasPrefix("__MACOSX/") &&
                !name.split(separator:"/").contains(where:{$0.hasPrefix(".")}) {
                guard flags & 1 == 0, method==0 || method==8 else {throw CaptureFileError.unsupportedZIP}
                let sentinel=Int(UInt32.max)
                if size==sentinel || packed==sentinel || local==sentinel {
                    var cursor=extrasAt
                    var received=false
                    while cursor+4 <= extrasAt+extra {
                        guard let kind=bytes.word(cursor,2),let len=bytes.word(cursor+2,2),
                              cursor+4+len<=extrasAt+extra else {throw CaptureFileError.invalidArchive}
                        let next=cursor+4+len
                        if kind==1 {
                            var field=cursor+4
                            func readWide() throws -> Int {
                                guard field<=next-8,let value=bytes.word(field,8) else {throw CaptureFileError.invalidArchive}
                                field+=8
                                return value
                            }
                            if size==sentinel {size=try readWide()}
                            if packed==sentinel {packed=try readWide()}
                            if local==sentinel {local=try readWide()}
                            received=true
                            break
                        }
                        cursor=next
                    }
                    guard received else {throw CaptureFileError.invalidArchive}
                }
                guard local <= bytes.count-30 else {throw CaptureFileError.invalidArchive}
                found.append(Entry(name:name,method:method,offset:local,packed:packed,size:size,crc:UInt32(crc)))
            }
            position=after
        }
        guard !found.isEmpty else {throw CaptureFileError.noScreenshots}
        entries=found.sorted {
            let order=$0.name.compare($1.name,options:[.numeric,.literal],locale:Locale(identifier:"en_US_POSIX"))
            return order == .orderedSame ? $0.offset < $1.offset : order == .orderedAscending
        }
    }

    func image(at index: Int, longestEdge: Int) throws -> CGImage {
        guard entries.indices.contains(index) else {throw CaptureFileError.invalidArchive}
        let entry=entries[index], offset=entry.offset
        guard data.word(offset,4)==0x04034b50,let nameSize=data.word(offset+26,2),let extra=data.word(offset+28,2) else {
            throw CaptureFileError.invalidArchive
        }
        let start=offset+30+nameSize+extra
        guard start <= data.count, entry.packed <= data.count-start, entry.size > 0,
              entry.size <= 128*1024*1024, entry.packed <= Int(UInt32.max) else {
            throw CaptureFileError.imageTooLarge
        }
        let png: Data
        if entry.method == 0 {
            guard entry.size==entry.packed else {throw CaptureFileError.invalidArchive}
            png=data.subdata(in:start..<start+entry.packed)
        } else {
            var output=Data(count:entry.size), stream=z_stream()
            guard inflateInit2_(&stream,-MAX_WBITS,ZLIB_VERSION,Int32(MemoryLayout<z_stream>.size)) == Z_OK else {
                throw CaptureFileError.invalidArchive
            }
            defer {inflateEnd(&stream)}
            let code=data.withUnsafeBytes { source in
                output.withUnsafeMutableBytes { dest in
                    stream.next_in=UnsafeMutablePointer(mutating:source.bindMemory(to:UInt8.self).baseAddress!.advanced(by:start))
                    stream.avail_in=uInt(entry.packed)
                    stream.next_out=dest.bindMemory(to:UInt8.self).baseAddress
                    stream.avail_out=uInt(entry.size)
                    return inflate(&stream,Z_FINISH)
                }
            }
            guard code==Z_STREAM_END, stream.total_out==entry.size else {throw CaptureFileError.invalidArchive}
            png=output
        }
        let crc=png.withUnsafeBytes {buf in crc32(0,buf.bindMemory(to:UInt8.self).baseAddress,uInt(buf.count))}
        guard UInt32(crc)==entry.crc, let source=CGImageSourceCreateWithData(png as CFData,nil),
              (CGImageSourceGetType(source) as String?) == "public.png" else {throw CaptureFileError.invalidArchive}
        let options:[CFString:Any]=[kCGImageSourceCreateThumbnailFromImageAlways:true,
                                    kCGImageSourceCreateThumbnailWithTransform:true,
                                    kCGImageSourceThumbnailMaxPixelSize:longestEdge]
        guard let frame=CGImageSourceCreateThumbnailAtIndex(source,0,options as CFDictionary) else {
            throw CaptureFileError.invalidArchive
        }
        return frame
    }
}

private extension Data {
    func word(_ offset:Int,_ width:Int)->Int? {
        guard offset>=0, offset<=count-width else {return nil}
        var result:UInt64=0
        for i in 0..<width {result |= UInt64(self[offset+i]) << (8*i)}
        return Int(exactly:result)
    }
}
enum CaptureFileError: LocalizedError {
    case invalidArchive, unsupportedZIP, imageTooLarge, noScreenshots, invalidVideo, hasAudio, format
    var errorDescription:String? {
        switch self {
        case .invalidArchive: return "This ZIP contains an unreadable screenshot or damaged directory."
        case .unsupportedZIP: return "Choose an unencrypted ZIP with stored or deflated PNG images."
        case .imageTooLarge: return "A screenshot exceeds the supported image size."
        case .noScreenshots: return "This ZIP contains no PNG screenshots."
        case .invalidVideo: return "This file does not contain a playable video."
        case .hasAudio: return "This video contains audio. Import a silent trajectory recording."
        case .format: return "Choose a screenshot ZIP or a silent MP4."
        }
    }
}
