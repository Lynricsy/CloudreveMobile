import Testing
@testable import OpenCloudreve

/// 外挂字幕解析（SRT / VTT / ASS）与按时间定位
struct SubtitleParserTests {
    @Test func parsesSrtWithTagsAndCrlf() {
        let srt = "\u{FEFF}1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>你好</i>，<b>世界</b>\r\n\r\n2\r\n00:00:04,000 --> 00:00:06,000\r\n{\\an8}第二行\r\n第三行\r\n"
        let cues = SubtitleParser.parse(srt, format: "srt")
        #expect(cues == [
            SubtitleCue(start: 1, end: 3.5, text: "你好，世界"),
            SubtitleCue(start: 4, end: 6, text: "第二行\n第三行"),
        ])
    }

    @Test func parsesVttShortTimestampsAndSettings() {
        let vtt = "WEBVTT\n\nintro\n00:01.250 --> 00:02.000 align:start position:10%\nHello\n\n01:00:00.000 --> 01:00:01.000\nLate\n"
        let cues = SubtitleParser.parse(vtt, format: "vtt")
        #expect(cues.count == 2)
        #expect(cues[0] == SubtitleCue(start: 1.25, end: 2, text: "Hello"))
        #expect(cues[1].start == 3600)
    }

    @Test func parsesAssDialogueWithCustomFormat() {
        let ass = """
        [Script Info]
        Title: 测试

        [V4+ Styles]
        Format: Name, Fontname
        Style: Default,Arial

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Comment: 0,0:00:00.00,0:00:05.00,Default,,0,0,0,,注释不显示
        Dialogue: 0,0:00:01.50,0:00:04.00,Default,,0,0,0,,{\\b1}粗体{\\b0}，逗号, 保留\\N第二行
        Dialogue: 0,0:00:02.00,0:00:03.00,Default,,0,0,0,,叠加\\h字幕
        """
        let cues = SubtitleParser.parse(ass, format: "ass")
        #expect(cues == [
            SubtitleCue(start: 1.5, end: 4, text: "粗体，逗号, 保留\n第二行"),
            SubtitleCue(start: 2, end: 3, text: "叠加 字幕"),
        ])
    }

    @Test func assHonoursFieldOrder() {
        let ass = "[Events]\nFormat: Start, End, Text\nDialogue: 0:00:10.00,0:00:12.00,换了顺序\n"
        #expect(SubtitleParser.parse(ass, format: "ssa") == [SubtitleCue(start: 10, end: 12, text: "换了顺序")])
    }

    @Test func skipsMalformedBlocks() {
        let srt = "1\n00:00:05,000 --> 00:00:04,000\n倒序\n\n2\nnot a timing\n文本\n\n3\n00:00:07,000 --> 00:00:08,000\n\n"
        #expect(SubtitleParser.parse(srt, format: "srt").isEmpty)
    }

    @Test func timestampVariants() {
        #expect(abs((SubtitleParser.timestamp("01:02:03,456") ?? 0) - 3723.456) < 1e-9)
        #expect(SubtitleParser.timestamp("2:03.5") == 123.5)
        #expect(SubtitleParser.timestamp("0:00:01.20") == 1.2)
        #expect(SubtitleParser.timestamp("abc") == nil)
        #expect(SubtitleParser.timestamp("12") == nil)
    }

    @Test func trackLocatesCueByTime() {
        let track = SubtitleTrack(cues: [
            SubtitleCue(start: 5, end: 6, text: "C"),
            SubtitleCue(start: 0, end: 2, text: "A"),
            SubtitleCue(start: 2, end: 4, text: "B"),
        ])
        #expect(track.text(at: -1) == nil)
        #expect(track.text(at: 0) == "A")
        #expect(track.text(at: 1.99) == "A")
        // 区间左闭右开：恰好在切换点显示后一条
        #expect(track.text(at: 2) == "B")
        #expect(track.text(at: 4.5) == nil)
        #expect(track.text(at: 5.5) == "C")
        #expect(track.text(at: 100) == nil)
    }

    @Test func trackMergesOverlappingCuesInStartOrder() {
        let track = SubtitleTrack(cues: [
            SubtitleCue(start: 0, end: 10, text: "长"),
            SubtitleCue(start: 3, end: 4, text: "短"),
            SubtitleCue(start: 8, end: 9, text: "后"),
        ])
        #expect(track.text(at: 3.5) == "长\n短")
        #expect(track.text(at: 6) == "长")
        #expect(track.text(at: 8.5) == "长\n后")
        #expect(SubtitleTrack(cues: []).text(at: 1) == nil)
    }

    @Test func parentUriOfFile() {
        #expect(ViewerRequest.parentUri(of: "cloudreve://my/a/b.md") == "cloudreve://my/a")
        #expect(ViewerRequest.parentUri(of: "cloudreve://my/b.md") == "cloudreve://my")
        #expect(ViewerRequest.parentUri(of: "cloudreve://my") == "cloudreve://my")
    }
}
