import SwiftUI
import UniformTypeIdentifiers

@main
struct WalkthroughCaptureApp: App {
    @StateObject private var store=CaptureStore()
    var body: some Scene {
        WindowGroup {CaptureHome().environmentObject(store)}
    }
}

enum CaptureStyle {
    static let accent=Color(uiColor:UIColor { $0.userInterfaceStyle == .dark ?
        UIColor(red:0.42,green:0.84,blue:0.69,alpha:1) : UIColor(red:0.05,green:0.38,blue:0.30,alpha:1) })
    static let paper=Color(uiColor:UIColor { $0.userInterfaceStyle == .dark ? .systemGroupedBackground :
        UIColor(red:0.95,green:0.96,blue:0.94,alpha:1) })
    static let card=Color(uiColor:.secondarySystemGroupedBackground)
}

struct CaptureHome:View {
    @EnvironmentObject private var store:CaptureStore
    @Environment(\.scenePhase) private var phase
    @State private var tab=0
    @State private var importing=false
    var body:some View {
        TabView(selection:$tab) {
            NavigationStack {setup}
                .tabItem {Label("Capture",systemImage:"viewfinder")}.tag(0)
            NavigationStack {
                CaptureLibrary(onImport:{importing=true})
            }.tabItem {Label("Library",systemImage:"rectangle.stack")}.tag(1)
        }
        .tint(CaptureStyle.accent)
        .fileImporter(isPresented:$importing,allowedContentTypes:[.zip,.mpeg4Movie],allowsMultipleSelection:true) { result in
            switch result {
            case .success(let urls): Task {await store.importFiles(urls)}
            case .failure(let err):
                if (err as NSError).code != NSUserCancelledError {store.error=err.localizedDescription}
            }
        }
        .onOpenURL { url in
            guard url.isFileURL else {return}
            Task {await store.importFiles([url])}
        }
        .sheet(item:$store.presented) { item in
            CaptureDetail(capture:item).environmentObject(store)
        }
        .alert("Capture needs attention",isPresented:Binding(get:{store.error != nil},set:{if !$0 {store.error=nil}})) {
            Button("OK") {store.error=nil}
        } message:{Text(store.error ?? "")}
        .task(id:phase) {
            guard phase == .active else{return}
            while !Task.isCancelled {
                await store.poll()
                do {try await Task.sleep(for:.seconds(2))} catch {break}
            }
        }
    }
    private var setup:some View {
        List {
            Section {
                ForEach(CaptureMode.allCases,id:\.self) { mode in
                    Button {
                        store.configuration.mode=mode
                    } label:{
                        HStack(spacing:14) {
                            Image(systemName:mode.symbol)
                                .font(.title2.weight(.medium)).frame(width:42,height:46)
                                .foregroundStyle(CaptureStyle.accent)
                            VStack(alignment:.leading,spacing:4) {
                                Text(mode.title).font(.headline).foregroundStyle(.primary)
                                Text(mode == .screenshots ? "Overlapping PNGs in one ZIP" : "Selected activities in one silent MP4")
                                    .font(.subheadline).foregroundStyle(.secondary)
                            }
                            Spacer(minLength:4)
                            Image(systemName:store.configuration.mode==mode ? "checkmark.circle.fill" : "circle")
                                .font(.title3).foregroundStyle(store.configuration.mode==mode ? CaptureStyle.accent : Color.secondary)
                        }
                        .padding(.vertical,6).contentShape(Rectangle())
                    }
                    .accessibilityAddTraits(store.configuration.mode==mode ? .isSelected : [])
                    .accessibilityIdentifier("capture-mode-"+mode.rawValue)
                    .buttonStyle(.plain)
                }
            } header:{Text("Output")}
            if store.configuration.mode == .screenshots {
                Section {
                    Stepper(value:$store.configuration.overlapPercent,in:40...85,step:5) {
                        LabeledContent("Screenshot overlap",value:store.configuration.overlapPercent.formatted(.number)+"%")
                    }.accessibilityIdentifier("screenshot-overlap")
                } footer:{Text("A larger overlap uses more screenshots to cover the conversation.")}
            } else {
                Section {
                    DatePicker("From",selection:dateBinding(start:true))
                    DatePicker("Through",selection:dateBinding(start:false))
                    Stepper(value:$store.configuration.holdSeconds,in:0.25...10,step:0.25) {
                        LabeledContent("Hold each view",value:store.configuration.holdSeconds.formatted(.number)+" s")
                    }.accessibilityIdentifier("hold-seconds")
                } header:{Text("Recording")}
                Section {
                    let choices=store.choicesInRange
                    if choices.isEmpty {
                        Text(store.trajectories.isEmpty ? "Run discovery on your Mac to load activities for this range." :
                                "No activities fall within these times.")
                            .font(.subheadline).foregroundStyle(.secondary)
                    } else {
                        Button(allSelected ? "Clear selection" : "Select all") {
                            store.configuration.selectedTrajectories=allSelected ? [] : choices.map(\.id)
                        }
                        ForEach(choices) { choice in
                            Button {
                                if store.configuration.selectedTrajectories.contains(choice.id) {
                                    store.configuration.selectedTrajectories.removeAll{$0==choice.id}
                                } else {store.configuration.selectedTrajectories.append(choice.id)}
                            } label:{
                                HStack(spacing:12) {
                                    Image(systemName:store.configuration.selectedTrajectories.contains(choice.id) ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(CaptureStyle.accent)
                                    VStack(alignment:.leading,spacing:4) {
                                        Text(choice.title).foregroundStyle(.primary)
                                        Text(choice.timestamp.formatted(date:.abbreviated,time:.shortened))
                                            .font(.caption).foregroundStyle(.secondary)
                                    }
                                }.frame(maxWidth:.infinity,alignment:.leading).contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(store.configuration.selectedTrajectories.contains(choice.id) ? .isSelected : [])
                            .accessibilityIdentifier("trajectory-"+choice.id)
                        }
                    }
                } header:{Text("Trajectories")}
            }
            Section {
                Label("The Mac runner operates the target app and sends the finished file back to this library.",systemImage:"desktopcomputer")
                    .font(.subheadline).foregroundStyle(.secondary).labelStyle(.titleAndIcon)
            } header:{Text("Run from your Mac")}
        }
        .listStyle(.insetGrouped).scrollContentBackground(.hidden)
        .background(CaptureStyle.paper)
        .navigationTitle("Capture setup")
        .safeAreaInset(edge:.bottom,spacing:0) {
            VStack(spacing:5) {
                if let message=store.savedMessage {
                    Label(message,systemImage:"checkmark.circle.fill")
                        .font(.footnote).foregroundStyle(CaptureStyle.accent)
                        .accessibilityIdentifier("config-saved")
                }
                Button {store.saveConfiguration()} label:{
                    Text("Save configuration").fontWeight(.semibold).frame(maxWidth:.infinity)
                }.buttonStyle(.borderedProminent).controlSize(.large)
                    .accessibilityIdentifier("save-configuration")
            }.padding(.horizontal,20).padding(.vertical,8).background(.regularMaterial)
        }
        .onChange(of:store.configuration){_,_ in store.savedMessage=nil}
    }
    private var allSelected:Bool {
        !store.choicesInRange.isEmpty && store.choicesInRange.allSatisfy{store.configuration.selectedTrajectories.contains($0.id)}
    }
    private func dateBinding(start:Bool)->Binding<Date> {
        Binding(get:{start ? store.configuration.startTime : store.configuration.endTime},set:{ date in
            guard let minute=Calendar.current.dateInterval(of:.minute,for:date) else{return}
            if start {store.configuration.startTime=minute.start}
            else {store.configuration.endTime=minute.end.addingTimeInterval(-1)}
        })
    }
}
