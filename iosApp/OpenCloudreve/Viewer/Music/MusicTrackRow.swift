import SwiftUI

struct MusicTrackRow: View {
    let track: MusicTrackItem

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: track.isCurrent ? "speaker.wave.2.fill" : "music.note")
                .foregroundStyle(track.isCurrent ? Color.accentColor : .secondary)
                .frame(width: 24)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: track.title)
                    .foregroundStyle(.primary)
                    .fontWeight(track.isCurrent ? .semibold : .regular)
                if let message = track.errorMessage {
                    Text(verbatim: message)
                        .font(.caption)
                        .foregroundStyle(.red)
                } else if let artist = track.artist {
                    Text(verbatim: artist)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
        }
        .contentShape(.rect)
        .accessibilityAddTraits(track.isCurrent ? .isSelected : [])
    }
}
