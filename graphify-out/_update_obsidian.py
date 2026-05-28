import json
from pathlib import Path
from multiprocessing import freeze_support
from networkx.readwrite import json_graph

from graphify.export import to_obsidian, to_canvas


LABELS = {
    0:  "App Preferences",
    1:  "Format Picker (Detected Videos)",
    2:  "Top Pages / Home Browser",
    3:  "Download Orchestration",
    4:  "Settings ViewModel",
    5:  "VideoTaskItem Model",
    6:  "Bookmarks UI",
    7:  "Custom File Downloader (Chunks)",
    8:  "Util DI Module",
    9:  "Proxy & Secure DNS",
    10: "Browser Cookie Decryption",
    11: "File / Storage Utilities",
    12: "Video Models (Generic)",
    13: "HLS Playlist Parser",
    14: "DNS Stamp / DNSCrypt",
    15: "Web Tab Fragment Lifecycle",
    16: "Page Info & Main ViewModel",
    17: "MPD / DASH Downloader",
    18: "Custom Regular Download Worker",
    19: "yt-dlp Downloader",
    20: "Proxy List UI",
    21: "Browser Fragment (Tabs)",
    22: "Web Tab ViewModel",
    23: "Web Tab UI Events",
    24: "Video Detection",
    25: "Fragment Factory",
    26: "Search Suggestions",
    27: "Download Progress",
    28: "Settings Fragment / Search Engine",
    29: "SuperX Download Worker",
}


def main():
    data = json.loads(Path('graphify-out/graph.json').read_text(encoding='utf-8'))
    G = json_graph.node_link_graph(data, edges='links')

    communities = {}
    for nid, ndata in G.nodes(data=True):
        cid = ndata.get('community')
        if cid is None:
            continue
        communities.setdefault(int(cid), []).append(nid)

    cohesion = {}
    for cid, nodes in communities.items():
        nset = set(nodes)
        internal = external = 0
        for u in nodes:
            for v in G.neighbors(u):
                if v in nset:
                    internal += 1
                else:
                    external += 1
        total = internal + external
        cohesion[cid] = (internal / total) if total else 0.0

    labels = {cid: LABELS.get(cid, f'Community {cid}') for cid in communities}

    out_dir = Path('graphify-out/obsidian')
    # Wipe stale node notes (community notes will be regenerated too).
    # Keep README.md and graph.canvas removed-and-replaced.
    preserved = {'README.md'}
    for entry in out_dir.iterdir():
        if entry.name in preserved:
            continue
        if entry.is_file():
            entry.unlink()
        else:
            # The exporter writes flat files; nested dirs aren't expected.
            for sub in entry.rglob('*'):
                if sub.is_file():
                    sub.unlink()

    n = to_obsidian(G, communities, str(out_dir), community_labels=labels, cohesion=cohesion)
    print(f'Obsidian vault: {n} notes refreshed in {out_dir}/')

    canvas_path = out_dir / 'graph.canvas'
    to_canvas(G, communities, str(canvas_path), community_labels=labels)
    print(f'Canvas: {canvas_path}')


if __name__ == '__main__':
    freeze_support()
    main()
