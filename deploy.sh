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
    cp -r resources/public/css resources/public/enum .
    cp resources/public/index_deploy.html index.html
    cp target/public/cljs-out/dev-main.js main.js
    git add css enum index.html main.js
    git commit -m "Push to GitHub Pages"
    git push origin gh-pages
    echo "Deployed!"
    git checkout -
else
    echo "Cannot check out gh-pages branch."
fi