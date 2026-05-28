import json
import shutil
from pathlib import Path
from multiprocessing import freeze_support

import networkx as nx
from networkx.readwrite import json_graph

from graphify.extract import collect_files, extract
from graphify.build import build_from_json
from graphify.cluster import cluster, score_all
from graphify.analyze import god_nodes, surprising_connections, suggest_questions, graph_diff
from graphify.report import generate
from graphify.export import to_json, to_html
from graphify.detect import detect, save_manifest


# Same labels as the initial build
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


def _copy_edges(G_src, G_dst, surviving):
    """Copy edges from G_src into G_dst when both endpoints survive.
    Works for both Graph and MultiGraph variants.
    """
    if G_src.is_multigraph():
        for u, v, k, edata in G_src.edges(keys=True, data=True):
            if u in surviving and v in surviving:
                G_dst.add_edge(u, v, key=k, **edata)
    else:
        for u, v, edata in G_src.edges(data=True):
            if u in surviving and v in surviving:
                G_dst.add_edge(u, v, **edata)


def main():
    incremental = json.loads(
        Path('graphify-out/.graphify_incremental.json').read_text(encoding='utf-8')
    )
    new_files = incremental.get('new_files', {})
    deleted = list(incremental.get('deleted_files', []))

    # Step 1 - back up the existing graph for the diff at the end
    shutil.copy('graphify-out/graph.json', 'graphify-out/.graphify_old.json')
    old_data = json.loads(Path('graphify-out/.graphify_old.json').read_text(encoding='utf-8'))
    G_old = json_graph.node_link_graph(old_data, edges='links')
    print(f"Old graph type: {type(G_old).__name__}, multigraph={G_old.is_multigraph()}")

    # Step 2 - AST-extract just the changed files
    changed = []
    for cat, files in new_files.items():
        for f in files:
            p = Path(f)
            changed.extend(collect_files(p) if p.is_dir() else [p])

    print(f"Re-extracting {len(changed)} changed file(s)...")
    new_extraction = extract(changed) if changed else {
        'nodes': [], 'edges': [], 'hyperedges': [],
        'input_tokens': 0, 'output_tokens': 0,
    }
    print(f"  AST: {len(new_extraction['nodes'])} nodes, {len(new_extraction['edges'])} edges")

    G_new = build_from_json(new_extraction)
    print(f"New subgraph type: {type(G_new).__name__}, multigraph={G_new.is_multigraph()}")

    # Step 3 - rebuild the merged graph cleanly. Strip every node from the old
    # graph that originated from a changed/deleted file, then union the
    # surviving nodes with the freshly extracted ones. This prevents stale
    # nodes (deleted methods, renames) from lingering.
    changed_paths = {Path(f).resolve() for files in new_files.values() for f in files}
    deleted_paths = {Path(f).resolve() for f in deleted}
    invalidated_paths = changed_paths | deleted_paths

    def belongs_to_invalidated(node_data):
        src = node_data.get('source_file')
        if not src:
            return False
        try:
            return Path(src).resolve() in invalidated_paths
        except Exception:
            return False

    # Pick a graph class that matches whichever side is multi
    if G_old.is_multigraph() or G_new.is_multigraph():
        G_merged = nx.MultiGraph()
    else:
        G_merged = nx.Graph()

    # Carry over surviving nodes from the old graph (with attrs)
    for nid, ndata in G_old.nodes(data=True):
        if belongs_to_invalidated(ndata):
            continue
        G_merged.add_node(nid, **ndata)

    surviving = set(G_merged.nodes())
    _copy_edges(G_old, G_merged, surviving)

    # Add freshly extracted nodes/edges
    for nid, ndata in G_new.nodes(data=True):
        if nid in G_merged.nodes:
            G_merged.nodes[nid].update(ndata)
        else:
            G_merged.add_node(nid, **ndata)

    surviving = set(G_merged.nodes())
    _copy_edges(G_new, G_merged, surviving)

    print(
        f"Merged graph: {G_merged.number_of_nodes()} nodes, {G_merged.number_of_edges()} edges "
        f"(was {G_old.number_of_nodes()} nodes, {G_old.number_of_edges()} edges)"
    )

    # Step 4 - re-cluster, re-analyze, regenerate outputs
    communities = cluster(G_merged)
    cohesion = score_all(G_merged, communities)
    gods = god_nodes(G_merged)
    surprises = surprising_connections(G_merged, communities)
    labels = {cid: LABELS.get(cid, f'Community {cid}') for cid in communities}

    # Detection block for the report header (re-run on the chosen scope)
    detection = detect(Path('app/src/main/java'))
    save_manifest(detection['files'])

    questions = suggest_questions(G_merged, communities, labels)
    tokens = {'input': 0, 'output': 0}

    report = generate(
        G_merged, communities, cohesion, labels, gods, surprises,
        detection, tokens, 'app/src/main/java',
        suggested_questions=questions,
    )
    Path('graphify-out/GRAPH_REPORT.md').write_text(report, encoding='utf-8')
    to_json(G_merged, communities, 'graphify-out/graph.json')
    Path('graphify-out/.graphify_labels.json').write_text(
        json.dumps({str(k): v for k, v in labels.items()}), encoding='utf-8'
    )

    if G_merged.number_of_nodes() <= 5000:
        to_html(G_merged, communities, 'graphify-out/graph.html', community_labels=labels)
        print('graph.html refreshed')

    # Step 5 - graph diff
    diff = graph_diff(G_old, G_merged)
    print()
    print(diff['summary'])
    new_node_labels = [n.get('label', n.get('id', '?')) for n in diff.get('new_nodes', [])][:10]
    if new_node_labels:
        print(f"  New nodes: {', '.join(new_node_labels)}")
    removed = diff.get('removed_nodes', [])
    if removed:
        removed_labels = [n.get('label', n.get('id', '?')) for n in removed][:10]
        print(f"  Removed nodes: {', '.join(removed_labels)}")
    print(f"  New edges: {len(diff.get('new_edges', []))}")
    if diff.get('removed_edges'):
        print(f"  Removed edges: {len(diff['removed_edges'])}")


if __name__ == '__main__':
    freeze_support()
    main()
