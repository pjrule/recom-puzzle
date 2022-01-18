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
    replace="s/main.js/main.js?$(sha256sum main.js | cut -d ' ' -f 1)/g;
             s/style.css/style.css?$(sha256sum css/style.css |  cut -d ' ' -f 1)/g"
    sed $replace resources/public/index_deploy.html > index.html
    git add main.js index.html
    git commit -m "Push to GitHub Pages"
    git push origin gh-pages
    echo "Deployed!"
    git checkout -
else
    echo "Cannot check out gh-pages branch."
fi
