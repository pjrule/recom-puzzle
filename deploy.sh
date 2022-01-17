#!/bin/bash
set -e

### Deployment to GitHub Pages. ###
if [ -z "git diff --exit-code" ]; then
    # git status check: https://unix.stackexchange.com/a/155077
    echo "Working directory not clean. :("
    exit 1
fi

clj -A:fig:min
if git checkout gh-pages; then 
    git checkout - resources/public
    cp target/public/cljs-out/dev-main.js main.js
    git add main.js
    git commit -m "Push to GitHub Pages"
    git push origin gh-pages
    echo "Deployed!"
    git checkout -
else
    echo "Cannot check out gh-pages branch."
fi
