"""Generates grid graphs."""
import networkx as nx

for n in range(4, 10):
    G = nx.convert_node_labels_to_integers(nx.grid_graph(dim=(n, n)))
    with open(f'{n}x{n}.dat', 'w') as f:
        for a, b in G.edges:
            print(1 + a, 1 + b, file=f)
