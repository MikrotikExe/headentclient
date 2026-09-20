import SwiftUI
import Shared
import MobileVLCKit

/// VLCKit player for the live stream. Unlike AVPlayer it handles raw
/// MPEG-TS over HTTP including MPEG-2 video and MP2/AC3 audio — which is most
/// DVB-S/T2 channels. The server does not have to transcode (pass profile).
///
/// Auth: credentials put straight into the URL (user:pass@host) — VLCKit takes
/// them from the URL. Works for both plain and digest via the libvlc HTTP stack.
struct PlayerView: UIViewRepresentable {
    let urlString: String

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .black
        let player = VLCMediaPlayer()
        context.coordinator.player = player
        player.drawable = view
        if let url = URL(string: urlString) {
            player.media = VLCMedia(url: url)
            player.play()
        }
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator() }

    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
        coordinator.player?.stop()
        coordinator.player = nil
    }

    class Coordinator {
        var player: VLCMediaPlayer?
    }
}

/// Player screen — fullscreen, with the channel name.
struct PlayerScreen: View {
    let channelUuid: String
    let channelTitle: String
    @Environment(\.dismiss) private var dismiss

    private var streamUrl: String? {
        guard let server = Tvh.shared.store.active() else { return nil }
        // liveUrl with creds in the URL (for VLCKit)
        return Tvh.shared.liveUrl(
            server: server,
            channelUuid: channelUuid,
            channelTitle: channelTitle,
            profile: server.profile.isEmpty ? "pass" : server.profile
        )
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let url = streamUrl {
                PlayerView(urlString: url).ignoresSafeArea()
            } else {
                Text(NSLocalizedString("no_active_server", comment: ""))
                    .foregroundColor(.white)
            }
            VStack {
                HStack {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title)
                            .foregroundColor(.white.opacity(0.8))
                    }
                    .padding()
                    Spacer()
                    Text(channelTitle).foregroundColor(.white).padding()
                }
                Spacer()
            }
        }
    }
}
