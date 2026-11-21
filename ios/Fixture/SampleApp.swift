import UIKit
import SwiftUI

// Original, offline UI for exercising scrolling, disclosures and activity selection.
@main
struct SampleApp: App {
    var body: some Scene { WindowGroup { SampleRoot().ignoresSafeArea() } }
}
private struct SampleRoot: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> SampleController { SampleController() }
    func updateUIViewController(_ controller: SampleController, context: Context) {}
}

private final class SampleController: UIViewController {
    enum Page { case chat, feed, steps(Int), detail(Int,Int) }
    let ink=UIColor(red:0.09,green:0.18,blue:0.17,alpha:1)
    let accent=UIColor(red:0.05,green:0.37,blue:0.31,alpha:1)
    let paper=UIColor(red:0.96,green:0.97,blue:0.95,alpha:1)
    let activities=[("river","Check river crossings","09:00"),("market","Compare market hours","09:05"),("garden","Survey garden paths","09:10")]
    let actions=[["Read the ferry timetable","Check the riverside path"],["Compare opening times","Mark the closest entrance"],["Read the garden map","Check accessible paths"]]
    var expanded=false
    var content=UIStackView(), bar=UIStackView(), disclosureBody=UILabel()
    override func viewDidLoad() { super.viewDidLoad(); view.backgroundColor=paper; show(.chat) }
    func text(_ s:String,_ size:CGFloat=17,_ weight:UIFont.Weight = .regular,_ id:String?=nil)->UILabel {
        let t=UILabel();t.text=s;t.textColor=ink;t.font = .systemFont(ofSize:size,weight:weight);t.numberOfLines=0;t.accessibilityIdentifier=id
        return t
    }
    func button(_ s:String,id:String,action:@escaping ()->Void)->UIButton {
        var c=UIButton.Configuration.tinted();c.title=s;c.baseForegroundColor=accent;c.cornerStyle = .medium
        c.contentInsets = .init(top:13,leading:14,bottom:13,trailing:14)
        let b=UIButton(configuration:c,primaryAction:UIAction{_ in action()});b.accessibilityIdentifier=id
        b.contentHorizontalAlignment = .leading; return b
    }
    func card(_ title:String,_ body:String) {
        let s=UIStackView(arrangedSubviews:[text(title,20,.semibold),text(body)])
        s.axis = .vertical;s.spacing=8;s.isLayoutMarginsRelativeArrangement=true;s.layoutMargins = .init(top:16,left:16,bottom:16,right:16)
        s.backgroundColor = .white;s.layer.cornerRadius=14;content.addArrangedSubview(s)
    }
    func layout(_ title:String,_ scrollID:String) {
        view.subviews.forEach{$0.removeFromSuperview()}
        bar=UIStackView();bar.spacing=10;bar.alignment = .center;bar.translatesAutoresizingMaskIntoConstraints=false
        let word=text(title,24,.bold);word.setContentHuggingPriority(.defaultLow,for:.horizontal);bar.addArrangedSubview(word)
        let scroll=UIScrollView();scroll.accessibilityIdentifier=scrollID;scroll.translatesAutoresizingMaskIntoConstraints=false
        content=UIStackView();content.axis = .vertical;content.spacing=14;content.translatesAutoresizingMaskIntoConstraints=false
        view.addSubview(bar);view.addSubview(scroll);scroll.addSubview(content)
        let safe=view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            bar.topAnchor.constraint(equalTo:safe.topAnchor,constant:6),bar.leadingAnchor.constraint(equalTo:safe.leadingAnchor,constant:16),
            bar.trailingAnchor.constraint(equalTo:safe.trailingAnchor,constant:-16),bar.heightAnchor.constraint(greaterThanOrEqualToConstant:50),
            scroll.topAnchor.constraint(equalTo:bar.bottomAnchor,constant:10),scroll.bottomAnchor.constraint(equalTo:safe.bottomAnchor),scroll.leadingAnchor.constraint(equalTo:safe.leadingAnchor),scroll.trailingAnchor.constraint(equalTo:safe.trailingAnchor),
            content.leadingAnchor.constraint(equalTo:scroll.contentLayoutGuide.leadingAnchor,constant:16),content.trailingAnchor.constraint(equalTo:scroll.contentLayoutGuide.trailingAnchor,constant:-16),
            content.topAnchor.constraint(equalTo:scroll.contentLayoutGuide.topAnchor,constant:6),content.bottomAnchor.constraint(equalTo:scroll.contentLayoutGuide.bottomAnchor,constant:-14),
            content.widthAnchor.constraint(equalTo:scroll.frameLayoutGuide.widthAnchor,constant:-32)])
    }
    func back(_ p:Page) { bar.insertArrangedSubview(button("Back",id:"nav.back"){[weak self] in self?.show(p)},at:0) }
    func boundary(_ s:String,_ id:String) { content.addArrangedSubview(text(s,15,.semibold,id)) }
    func show(_ p:Page) {
        switch p {
        case .chat:
            layout("Harbor Notes","chat.scroll")
            bar.addArrangedSubview(button("Activity",id:"profile.open"){[weak self] in self?.show(.feed)})
            boundary("Saturday field notes","chat.start")
            card("An afternoon by the harbor","Choose a walking route that leaves time for the small ferry and a picnic near the gardens.")
            let toggle=button(expanded ? "Hide planning notes" : "Show planning notes",id:"chat.thinking"){[weak self] in
                guard let self=self else{return};self.expanded.toggle();self.disclosureBody.isHidden = !self.expanded
                if let b=self.content.arrangedSubviews.compactMap({$0 as? UIButton}).first {
                    b.configuration?.title=self.expanded ? "Hide planning notes" : "Show planning notes"; b.accessibilityValue=self.expanded ? "expanded" : "collapsed"
                }
            }
            toggle.accessibilityValue=expanded ? "expanded" : "collapsed";content.addArrangedSubview(toggle)
            disclosureBody=text("The picnic table is close to the garden gate. The longer riverside path can wait until after the ferry arrives.")
            disclosureBody.isHidden = !expanded;content.addArrangedSubview(disclosureBody)
            content.addArrangedSubview(text("Route comparison",21,.semibold))
            addTable()
            card("The ferry stop","Start at the east pier. Allow ten minutes to reach the ticket window and another five to walk to the boarding gate.")
            card("The market","Pick up bread and fruit at the north entrance. The shaded side of the square has benches and a drinking fountain.")
            card("The gardens","The south gate opens onto a wide gravel path. Follow it toward the pond for an easy walk back to the ferry.")
            card("Before leaving","Bring a light jacket, fill your bottle at the pier and check the posted return departure.")
            boundary("End of field notes","chat.end")
        case .feed:
            layout("Activity","activity.list");back(.chat);boundary("April 5, 2025","activity.start")
            for (i,a) in activities.enumerated() {
                let b=button(a.1,id:"activity."+a.0){[weak self] in self?.show(.steps(i))}
                b.configuration?.subtitle=a.2+" UTC";b.accessibilityLabel=a.1;b.accessibilityValue="2025-04-05T"+a.2+":00Z";content.addArrangedSubview(b)
            }
            boundary("All three activities","activity.end")
        case let .steps(i):
            layout("Activity steps","steps.list");back(.feed);boundary(activities[i].1,"steps.start")
            for (j,s) in actions[i].enumerated() {content.addArrangedSubview(button(s,id:"step."+activities[i].0+"-"+String(j+1)){[weak self] in self?.show(.detail(i,j))})}
            boundary("End of steps","steps.end")
        case let .detail(i,j):
            layout("Step detail","detail.scroll");back(.steps(i));boundary(actions[i][j],"detail.start")
            card("Observation",["The posted schedule is consistent with the afternoon route.","The route fits the return window and leaves time for a stop."][j])
            card("Field note","The sample activity and its details are stored locally.")
            boundary("End of this step","detail.end")
        }
    }
    func addTable() {
        let scroll=UIScrollView();scroll.accessibilityIdentifier="chat.table";scroll.showsHorizontalScrollIndicator=true
        let row=UIStackView();row.spacing=8;row.translatesAutoresizingMaskIntoConstraints=false;scroll.addSubview(row)
        for (i,pair) in [("Ferry","East pier"),("Market","North door"),("Garden","South gate"),("Return","West pier")].enumerated() {
            let label=text(pair.0+"\n"+pair.1,18,.medium,i==0 ? "chat.table.start" : i==3 ? "chat.table.end" : nil)
            label.textAlignment = .center;label.backgroundColor = .white;label.layer.cornerRadius=12;label.clipsToBounds=true
            label.widthAnchor.constraint(equalToConstant:163).isActive=true;row.addArrangedSubview(label)
        }
        content.addArrangedSubview(scroll)
        NSLayoutConstraint.activate([scroll.heightAnchor.constraint(equalToConstant:92),row.topAnchor.constraint(equalTo:scroll.contentLayoutGuide.topAnchor),row.bottomAnchor.constraint(equalTo:scroll.contentLayoutGuide.bottomAnchor),row.leadingAnchor.constraint(equalTo:scroll.contentLayoutGuide.leadingAnchor),row.trailingAnchor.constraint(equalTo:scroll.contentLayoutGuide.trailingAnchor),row.heightAnchor.constraint(equalTo:scroll.frameLayoutGuide.heightAnchor)])
    }
}
