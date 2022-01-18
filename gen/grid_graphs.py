"""Generates grid graphs."""
import networkx as nx

for width in range(4, 10):
    for height in range(width, 10):
        G = nx.convert_node_labels_to_integers(nx.grid_graph(dim=(width, height)))
        with open(f'{width}x{height}.dat', 'w') as f:
            for a, b in G.edges:
                print(1 + a, 1 + b, file=f)
