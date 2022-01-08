# recom-puzzle

This project aims to accelerate exploration of the [ReCom metagraph](https://mggg.org/metagraph) of grid graph partitionings. It is still unknown whether the class of metagraphs induced by balanced _n_-partitionings of the _n_ ⨉ _m_ grid graphs are all connected, though empirical results suggest this holds when _m_ = _n_ and _n_ ≤ 8. I aim to build some intuition for this reconfiguration problem by turning canonicalization (that is, converting a starting plan to a striped configuration) into an interactive puzzle game.

## Development

To get an interactive development environment run:

    clojure -A:fig:build

This will auto compile and send all changes to the browser without the
need to reload. After the compilation process is complete, you will
get a Browser Connected REPL. An easy way to try it is:

    (js/alert "Am I connected?")

and you should see an alert in the browser window.

To clean all compiled files:

    rm -rf target/public

To create a production build run:

	rm -rf target/public
	clojure -A:fig:min


## License

Copyright © 2022 Parker J. Rule

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
